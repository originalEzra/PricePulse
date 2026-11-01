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
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import java.net.ServerSocket;
import org.testcontainers.junit.jupiter.*;

/** Shared dynamic runtime + public simulation service + actual disposable Broker; no provider socket. */
@SpringBootTest(properties={
        "price-pulse.messaging.publisher-initial-delay-ms=600000"
})
@ActiveProfiles("integration")
@Import(DynamicMonitoringInfrastructureIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class V21DeliveryRegressionIT {
    static final String RUN=UUID.randomUUID().toString(),TOPIC="price-pulse-v21-isolated-regression";
    static final Network NETWORK=Network.newNetwork();
    @Container static final GenericContainer<?> NAMESRV=new GenericContainer<>("apache/rocketmq:5.3.2")
            .withNetwork(NETWORK).withNetworkAliases("delivery-namesrv").withExposedPorts(9876)
            .withEnv("JAVA_OPT_EXT","-Xms128m -Xmx128m -Xmn64m").withCommand("sh","mqnamesrv").waitingFor(Wait.forListeningPort());
    @Container static final TestBroker BROKER=new TestBroker().withNetwork(NETWORK).dependsOn(NAMESRV);
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("v21_delivery_regression");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        var bootstrap=new DefaultMQProducer("delivery-bootstrap-"+RUN);
        bootstrap.setNamesrvAddr(NAMESRV.getHost()+":"+NAMESRV.getMappedPort(9876));
        try {
            bootstrap.start();boolean ready=false;long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
            do {try {bootstrap.createTopic("TBW102",TOPIC,2,Map.of());if(!bootstrap.fetchPublishMessageQueues(TOPIC).isEmpty()){ready=true;break;}}
                catch(org.apache.rocketmq.client.exception.MQClientException unavailable){Thread.sleep(200);}
            }while(System.nanoTime()<deadline);
            if(!ready)throw new IllegalStateException("Isolated delivery topic did not become ready");
        }catch(org.apache.rocketmq.client.exception.MQClientException|InterruptedException e){throw new IllegalStateException("Isolated Broker initialization failed",e);}
        finally {bootstrap.shutdown();}
        r.add("rocketmq.name-server",()->NAMESRV.getHost()+":"+NAMESRV.getMappedPort(9876));
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
            var otherTarget=rules.create(b,new SaveAlertRuleRequest(stock,"Other owner's target",null,AlertDirection.ABOVE,new BigDecimal("100")));
            runtime.rule(a,stock,"Opening gap once",350);
            rules.create(a,new SaveAlertRuleRequest(stock,"Second opening target",null,AlertDirection.ABOVE,new BigDecimal("350")));
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
            assertThat(opening.points()).hasSize(1);assertThat(opening.triggered()).isEqualTo(3);
            assertThat(simulations.advance(a,run.runId(),new Advance(0L,0L),openingKey)).isEqualTo(opening);
            assertThat(realFacts()).isEqualTo(before);
            assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",run.runId())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM simulation_rule_events WHERE run_id=?",run.runId())).isEqualTo(3);

            // Cancel before delivery: old committed facts must still be delivered and remain visible.
            var preview=runtime.service.cancellation(a,stock);
            var cancelled=runtime.service.cancel(a,stock,new Cancel(preview.version(),preview.rulesDigest(),true));
            assertThat(cancelled.deletedRules()).isEqualTo(3);
            runtime.service.reconcile();assertThat(runtime.wire.sent("unsubscribe","PLTR")).isZero();
            assertThat(count("SELECT COUNT(*) FROM alert_rules WHERE user_id=? AND status<>'DELETED'",a)).isZero();
            assertThat(runtime.service.list(b).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");
            // The synthetic feed clock is fixed in October; MySQL's container wall clock is later.
            // Make this fixture's committed messages due at that same test clock, without changing production dispatch.
            jdbc.update("UPDATE outbox_events SET next_retry_at=? WHERE status='PENDING'",LocalDateTime.ofInstant(runtime.clock.instant(),ZoneOffset.UTC));
            assertThat(publisher.publishBatch()).isEqualTo(2);await(()->count("SELECT COUNT(*) FROM notifications")==2);
            assertThat(notifications.feed(a).totalCount()).isEqualTo(1);assertThat(notifications.feed(b).totalCount()).isEqualTo(1);

            String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?",String.class,realTrigger);
            long deliveries=deliveries();mq.syncSend(TOPIC,payload);await(()->deliveries()>deliveries);
            assertThat(count("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",realTrigger)).isEqualTo(1);
            assertThat(publisher.publishBatch()).isZero();

            // Frozen simulation survives cancellation of its source rules, without changing REAL facts.
            var cancelledFacts=realFacts();var hour=simulations.advance(a,run.runId(),new Advance(1L,1L),key());
            assertThat(hour.points()).hasSize(60);assertThat(realFacts()).isEqualTo(cancelledFacts);
            runtime.tradeAndAwait("PLTR",stock,99);runtime.tradeAndAwait("PLTR",stock,101);
            assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",otherTarget.id())).isEqualTo(1);
            assertThat(rules.get(b,otherTarget.id()).status()).isEqualTo(AlertRuleStatus.COMPLETED);
            assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",once.id())).isEqualTo(1);
            assertThat(publisher.publishBatch()).isZero();assertThat(count("SELECT COUNT(*) FROM notifications")).isEqualTo(2);
            assertThat(notifications.feed(a).totalCount()).isEqualTo(1);assertThat(notifications.feed(b).totalCount()).isEqualTo(1);

            runtime.cancel(b,stock);runtime.service.reconcile();assertThat(runtime.wire.sent("unsubscribe","PLTR")).isEqualTo(1);
            assertThat(runtime.store.reserved()).isEqualTo(1);runtime.wire.ack();runtime.service.reconcile();assertThat(runtime.store.reserved()).isZero();
            assertThat(runtime.wire.connections).isEqualTo(1);assertThat(publisher.publishBatch()).isZero();
            assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status='SENT'")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM notifications n JOIN alert_triggers t ON t.id=n.trigger_id JOIN quote_events q ON q.id=t.quote_event_id WHERE q.origin_type<>'REAL'")).isZero();
            Path directory=Path.of("target/v21-stage4-20261030");Files.createDirectories(directory);
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("combined-delivery-regression.json").toFile(),Map.of(
                    "fixture","SYNTHETIC_DISPOSABLE_NO_PROVIDER_SOCKET","sharedSubscribeCommands",1,"lastUserUnsubscribeCommands",1,
                    "deletedOwnersRules",3,"committedRealTriggers",2,"sentOutbox",2,"formalNotifications",2,
                    "duplicateDeliveryKeptSingleNotification",true,"simulationOpeningRetryAddedNothing",true,"simulationKeptRealFactsUnchanged",true));
        }finally{runtime.stop();}
    }
    /** Same isolated advertised-port arrangement as the opening-gap Broker regression. */
    static class TestBroker extends GenericContainer<TestBroker> {
        TestBroker(){
            super("apache/rocketmq:5.3.2");int port;
            try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}catch(java.io.IOException e){throw new IllegalStateException(e);}
            addFixedExposedPort(port,port);addFixedExposedPort(port-2,port-2);
            withEnv("JAVA_OPT_EXT","-Xms256m -Xmx256m -Xmn128m -XX:MaxDirectMemorySize=128m");
            withCopyToContainer(Transferable.of("brokerClusterName=delivery-test\nbrokerName=delivery-broker\nbrokerId=0\nbrokerIP1=127.0.0.1\nlistenPort="+port+"\nautoCreateTopicEnable=true\nbrokerRole=ASYNC_MASTER\nmappedFileSizeCommitLog=67108864\ndiskMaxUsedSpaceRatio=95\n"),"/home/rocketmq/conf/delivery.conf");
            withCommand("sh","mqbroker","-n","delivery-namesrv:9876","-c","/home/rocketmq/conf/delivery.conf");waitingFor(Wait.forListeningPort());
        }
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
