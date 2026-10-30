package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.monitoring.*;
import com.ezra.pricepulse.monitoring.MonitoringModels.Cancel;
import com.ezra.pricepulse.notification.NotificationService;
import com.ezra.pricepulse.outbox.RocketMqOutboxPublisher;
import com.ezra.pricepulse.simulation.*;
import com.ezra.pricepulse.simulation.SimulationApi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Shared dynamic runtime + public simulation service + actual disposable Broker; no provider socket. */
@SpringBootTest(properties={
        "rocketmq.name-server=${ROCKETMQ_NAMESERVER:127.0.0.1:39876}",
        "price-pulse.messaging.publisher-initial-delay-ms=600000"
})
@ActiveProfiles("integration")
@Import(DynamicMonitoringInfrastructureIT.Fixtures.class)
@Testcontainers
class V21DeliveryRegressionIT {
    static final String RUN=UUID.randomUUID().toString(),TOPIC="price-pulse-v21-isolated-regression";
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("v21_delivery_regression");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"isolated-v21-delivery-key-at-least-32-bytes");
        r.add("price-pulse.monitoring.enabled",()->true);r.add("price-pulse.monitoring.dynamic-subscriptions-enabled",()->true);
        r.add("price-pulse.monitoring.reconcile-ms",()->3600000);r.add("price-pulse.alpaca.enabled",()->false);
        r.add("price-pulse.messaging.enabled",()->true);r.add("price-pulse.messaging.topic",()->TOPIC);
        r.add("price-pulse.messaging.consumer-group",()->"v21-regression-consumer-"+RUN);
        r.add("rocketmq.producer.group",()->"v21-regression-producer-"+RUN);
    }
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired JdbcTemplate jdbc;
    @Autowired SimulationService simulations;
    @Autowired AlertRuleService rules;
    @Autowired NotificationService notifications;
    @Autowired RocketMqOutboxPublisher publisher;
    @Autowired RocketMQTemplate mq;
    @Autowired MeterRegistry metrics;
    @Autowired ObjectMapper json;

    @Test @Timeout(120) void sharedDemandCancellationPendingDeliveryAndGapRetriesKeepRealAndSimulationIsolated()throws Exception{
        var runtime=new DynamicMonitoringInfrastructureIT();beans.autowireBean(runtime);
        try{
            runtime.prepare();long a=runtime.user(),b=runtime.user();
            var one=runtime.add(a,"PLTR");var two=runtime.add(b,"PLTR");long stock=one.stock().id();
            assertThat(two.stock().id()).isEqualTo(stock);runtime.service.reconcile();runtime.wire.ack();runtime.service.reconcile();
            assertThat(runtime.wire.sent("subscribe","PLTR")).isEqualTo(1);
            var once=runtime.rule(a,stock,"Cancelled owner's real alert",100);
            var repeat=rules.create(b,new SaveAlertRuleRequest(stock,"Other owner's repeating alert",null,AlertDirection.ABOVE,new BigDecimal("100"),AlertTriggerMode.REPEAT,new BigDecimal("99")));
            runtime.rule(a,stock,"Opening gap once",350);
            rules.create(a,new SaveAlertRuleRequest(stock,"Opening gap repeat",null,AlertDirection.ABOVE,new BigDecimal("350"),AlertTriggerMode.REPEAT,new BigDecimal("349")));
            jdbc.update("UPDATE alert_rules SET effective_at='2026-10-29 00:00:00' WHERE user_id IN (?,?)",a,b);
            runtime.tradeAndAwait("PLTR",stock,99);runtime.tradeAndAwait("PLTR",stock,101);
            await(()->count("SELECT COUNT(*) FROM alert_triggers")==2);
            assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM notifications")).isZero();
            long realTrigger=jdbc.queryForObject("SELECT id FROM alert_triggers WHERE rule_id=?",Long.class,once.id());

            // Custom simulated close is the prior cached session. All dates/prices belong to this fixture.
            jdbc.update("INSERT INTO market_sessions(provider,session_date,opens_at,closes_at,fetched_at) VALUES('ALPACA','2026-10-29','2026-10-29 13:30:00','2026-10-29 20:00:00',NOW(6))");
            var request=new Start(stock,new Origin(OriginKind.SIMULATION_REFERENCE,null,null),SimulationGenerator.Mode.OPENING_GAP,new BigDecimal("0.003"),42L,null,
                    new OpeningGap(ReferenceSource.CUSTOM,new BigDecimal("345"),new BigDecimal("355"),SimulationGenerator.Mode.FALL));
            var before=realFacts();var run=simulations.start(a,request,key());String openingKey=key();
            var opening=simulations.advance(a,run.runId(),new Advance(0L,0L),openingKey);
            assertThat(opening.points()).hasSize(1);assertThat(opening.triggered()).isEqualTo(2);
            assertThat(simulations.advance(a,run.runId(),new Advance(0L,0L),openingKey)).isEqualTo(opening);
            assertThat(realFacts()).isEqualTo(before);
            assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",run.runId())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM simulation_rule_events WHERE run_id=?",run.runId())).isEqualTo(2);

            // Cancel before delivery: old committed facts must still be delivered and remain visible.
            var preview=runtime.service.cancellation(a,stock);
            var cancelled=runtime.service.cancel(a,stock,new Cancel(preview.version(),preview.rulesDigest(),true));
            assertThat(cancelled.deletedRules()).isEqualTo(3);
            runtime.service.reconcile();assertThat(runtime.wire.sent("unsubscribe","PLTR")).isZero();
            assertThat(count("SELECT COUNT(*) FROM alert_rules WHERE user_id=? AND status<>'DELETED'",a)).isZero();
            assertThat(runtime.service.list(b).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");
            assertThat(publisher.publishBatch()).isEqualTo(2);await(()->count("SELECT COUNT(*) FROM notifications")==2);
            assertThat(notifications.feed(a).totalCount()).isEqualTo(1);assertThat(notifications.feed(b).totalCount()).isEqualTo(1);

            String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?",String.class,realTrigger);
            long deliveries=deliveries();mq.syncSend(TOPIC,payload);await(()->deliveries()>deliveries);
            assertThat(count("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",realTrigger)).isEqualTo(1);
            assertThat(publisher.publishBatch()).isZero();

            // Frozen simulation survives cancellation of its source rules, without changing REAL facts.
            var cancelledFacts=realFacts();var hour=simulations.advance(a,run.runId(),new Advance(1L,1L),key());
            assertThat(hour.points()).hasSize(60);assertThat(hour.rearmed()).isEqualTo(1);assertThat(realFacts()).isEqualTo(cancelledFacts);
            runtime.tradeAndAwait("PLTR",stock,99);runtime.tradeAndAwait("PLTR",stock,101);
            await(()->count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",repeat.id())==2);
            assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",once.id())).isEqualTo(1);
            assertThat(publisher.publishBatch()).isEqualTo(1);await(()->count("SELECT COUNT(*) FROM notifications")==3);
            assertThat(notifications.feed(a).totalCount()).isEqualTo(1);assertThat(notifications.feed(b).totalCount()).isEqualTo(2);

            runtime.cancel(b,stock);runtime.service.reconcile();assertThat(runtime.wire.sent("unsubscribe","PLTR")).isEqualTo(1);
            assertThat(runtime.store.reserved()).isEqualTo(1);runtime.wire.ack();runtime.service.reconcile();assertThat(runtime.store.reserved()).isZero();
            assertThat(runtime.wire.connections).isEqualTo(1);assertThat(publisher.publishBatch()).isZero();
            assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status='SENT'")).isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM notifications n JOIN alert_triggers t ON t.id=n.trigger_id JOIN quote_events q ON q.id=t.quote_event_id WHERE q.origin_type<>'REAL'")).isZero();
            Path directory=Path.of("target/v21-stage4-20261030");Files.createDirectories(directory);
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("combined-delivery-regression.json").toFile(),Map.of(
                    "fixture","SYNTHETIC_DISPOSABLE_NO_PROVIDER_SOCKET","sharedSubscribeCommands",1,"lastUserUnsubscribeCommands",1,
                    "deletedOwnersRules",3,"committedRealTriggers",3,"sentOutbox",3,"formalNotifications",3,
                    "duplicateDeliveryKeptSingleNotification",true,"simulationOpeningRetryAddedNothing",true,"simulationKeptRealFactsUnchanged",true));
        }finally{runtime.stop();}
    }
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    String key(){return UUID.randomUUID().toString();}
    List<?> realFacts(){return List.of(jdbc.queryForList("SELECT id,latest_price,latest_quote_at,rule_index_token FROM stocks ORDER BY id"),
            jdbc.queryForList("SELECT id,status,version,effective_at FROM alert_rules ORDER BY id"),count("SELECT COUNT(*) FROM quote_events"),count("SELECT COUNT(*) FROM alert_triggers"),count("SELECT COUNT(*) FROM outbox_events"),count("SELECT COUNT(*) FROM notifications"));}
    long deliveries(){return (long)metrics.get("pricepulse.notification.deliveries").tag("outcome","success").counter().count();}
    static void await(BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("isolated delivery condition").isTrue();
    }
}
