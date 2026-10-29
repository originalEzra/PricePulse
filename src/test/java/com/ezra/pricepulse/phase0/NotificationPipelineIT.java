package com.ezra.pricepulse.phase0;

import static org.assertj.core.api.Assertions.assertThat;

import com.ezra.pricepulse.PricePulseApplication;
import com.ezra.pricepulse.outbox.RocketMqOutboxPublisher;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.simulation.SimulationIsolationService;
import com.ezra.pricepulse.simulation.SimulationModels.*;
import com.ezra.pricepulse.notification.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.nio.file.*;
import java.net.*;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;
import java.time.Duration;
import java.util.UUID;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = PricePulseApplication.class, properties = {
        "price-pulse.security.jwt.secret=price-pulse-mq-pipeline-jwt-secret-at-least-32-bytes",
        "price-pulse.messaging.enabled=true",
        "price-pulse.alpaca.enabled=false",
        "price-pulse.alpaca.business-writes-enabled=false",
        "price-pulse.messaging.publisher-initial-delay-ms=600000",
        "rocketmq.name-server=${ROCKETMQ_NAMESERVER:127.0.0.1:19876}"
})
@ActiveProfiles("mq-pipeline-it")
@Import(RealQuoteTestSupport.class)
@Testcontainers
class NotificationPipelineIT {
    private static final String RUN = UUID.randomUUID().toString();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7")
            .withDatabaseName("price_pulse_mq_test");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.6-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/local");
        registry.add("rocketmq.producer.group", () -> "price-pulse-pipeline-producer-" + RUN);
        registry.add("price-pulse.messaging.topic", () -> "price-pulse-notification-pipeline-it");
        registry.add("price-pulse.messaging.consumer-group", () -> "price-pulse-pipeline-consumer-" + RUN);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RocketMQTemplate rocketMq;
    @Autowired RocketMqOutboxPublisher publisher;
    @Autowired RealQuoteTestSupport.Fixture market;
    @Autowired SimulationIsolationService simulations;
    @Autowired NotificationService notifications;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry metrics;

    @Test void controlledRealIngestionUsesBrokerWhileTwoSimulationBranchesNeverPublish() throws Exception {
        long owner = owner(), stock = stock(owner);
        crossing(owner, stock);
        long trigger = trigger(stock);
        publisher.publishAvailable();
        awaitCount("SELECT COUNT(*) FROM notifications WHERE trigger_id=?", trigger, 1);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
        var seed = new Seed(new BigDecimal("95"), Instant.parse("2024-10-29T13:30:00Z"), json.createObjectNode(),
                json.createArrayNode(), json.createObjectNode(), 42, "p5-controlled-isolation", json.createObjectNode());
        for (int i=0;i<2;i++) {
            long person = i==0 ? owner : owner();
            if (i!=0) rule(person,stock);
            String key=UUID.randomUUID().toString();
            String hash="0".repeat(64); // Internal fixture metadata; public APIs compute the normalized request hash.
            var run=simulations.begin(person,stock,null,key,hash,seed);
            var result=simulations.advance(person,run.id(),0,0,key,hash,
                    ignored -> List.of(new Point(seed.time().plusSeconds(60),new BigDecimal("101"))));
            assertThat(result.triggered()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events",Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id IN (SELECT id FROM alert_rules WHERE stock_id=?)",Long.class,stock)).isEqualTo(1);
        assertThat(publisher.publishBatch()).isZero();
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?",String.class,trigger);
        long deliveries=deliveries();rocketMq.syncSend("price-pulse-notification-pipeline-it",payload);
        await(() -> deliveries()>deliveries);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",Long.class,trigger)).isEqualTo(1);
    }

    @Test void oldBrokerMessagesAndEveryLegacyBacklogStatusStayOutsideFormalNotifications() throws Exception {
        long owner=owner(),stock=stock(owner);crossing(owner,stock);long trigger=trigger(stock);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?",String.class,trigger);
        jdbc.update("UPDATE quote_events SET origin_type='LEGACY' WHERE stock_id=?",stock);
        for(String status:List.of("FAILED","SENDING"))jdbc.update("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status,attempts,locked_by,locked_at) VALUES('ALERT_TRIGGER',?,'ALERT_TRIGGERED',?,?,2,'old-v1-worker',NOW(6))",trigger,payload,status);
        publisher.publishAvailable();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND status='QUARANTINED'",Long.class,trigger)).isEqualTo(3);
        long deliveries=deliveries();rocketMq.syncSend("price-pulse-notification-pipeline-it",payload);
        await(() -> deliveries()>deliveries);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",Long.class,trigger)).isZero();
        assertThat(notifications.feed(owner).totalCount()).isZero();assertThat(notifications.markAllRead(owner).markedRead()).isZero();
    }

    @Test @Timeout(90)
    @EnabledIfEnvironmentVariable(named="PRICEPULSE_P5_BROKER_CONTAINER",matches="pricepulse-v2-p5-.+")
    void actualDisposableBrokerOutageLeavesDurableFailureAndRecoversOnce() throws Exception {
        var docker=DockerClientFactory.instance().client();String container=System.getenv("PRICEPULSE_P5_BROKER_CONTAINER");
        var info=docker.inspectContainerCmd(container).exec();
        assertThat(info.getConfig().getLabels().get("com.docker.compose.project")).startsWith("pricepulse-v2-p5-");
        long owner=owner(),stock=stock(owner);crossing(owner,stock);long trigger=trigger(stock),started=System.nanoTime();
        try {
            docker.stopContainerCmd(container).withTimeout(3).exec();
            publisher.publishAvailable();
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id=?",String.class,trigger)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",Long.class,trigger)).isZero();
        } finally { docker.startContainerCmd(container).exec(); }
        int port=Integer.parseInt(System.getenv("PRICEPULSE_P5_BROKER_PORT"));
        await(() -> {try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),250);return true;}catch(Exception unavailable){return false;}});
        await(() -> {publisher.publishAvailable();return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",Long.class,trigger)==1;});
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id=?",String.class,trigger)).isEqualTo("SENT");
        Path directory=Path.of(System.getProperty("pricepulse.p5.report-dir","target/v2-p5"));Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("broker-outage.json").toFile(),Map.of(
                "fixture","SYNTHETIC_DISPOSABLE_ONLY","actualBrokerStopped",true,"durableFailureObserved",true,
                "businessNotifications",1,"recoveryMs",(System.nanoTime()-started)/1_000_000.0,"developmentDatabaseConnected",false));
    }

    private long owner(){String email=UUID.randomUUID()+"@p5.test";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'unused','P5 Synthetic')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    private long stock(long owner){String symbol="P5"+UUID.randomUUID().toString().substring(0,8);jdbc.update("INSERT INTO stocks(symbol,name,market,currency) VALUES(?,'P5 synthetic','SIM','USD')",symbol);long id=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol=?",Long.class,symbol);rule(owner,id);return id;}
    private void rule(long owner,long stock){jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,status) VALUES(?,?,'P5 rule','ABOVE',100,'ONCE','ACTIVE')",owner,stock);}
    private void crossing(long owner,long stock){for(int i=0;i<2;i++)market.replay(owner,new ReplayRequest(List.of(new QuoteInputRequest(UUID.randomUUID().toString(),stock,new BigDecimal(i==0?"95":"101"),LocalDateTime.of(2024,10,29,13,30).plusSeconds(i),"P5_SYNTHETIC",(long)i))));}
    private long trigger(long stock){return jdbc.queryForObject("SELECT t.id FROM alert_triggers t JOIN quote_events q ON q.id=t.quote_event_id WHERE q.stock_id=?",Long.class,stock);}
    private long deliveries(){return (long)metrics.get("pricepulse.notification.deliveries").tag("outcome","success").counter().count();}
    private void await(BooleanSupplier condition) throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(200);}assertThat(condition.getAsBoolean()).isTrue();}

    @Test
    void committedOutboxTravelsThroughRealBrokerAndCreatesOneNotification() throws Exception {
        String suffix = RUN.substring(0, 8);
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name)
                VALUES (?, 'not-used-in-this-test', 'Pipeline Test')
                """, "pipeline-" + suffix + "@example.com");
        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "pipeline-" + suffix + "@example.com");
        jdbc.update("""
                INSERT INTO stocks (symbol, name, market, currency, latest_price, latest_quote_at)
                VALUES (?, 'Pipeline Stock', 'SIM', 'USD', 101.0000, NOW(6))
                """, "MQ" + suffix.toUpperCase());
        long stockId = jdbc.queryForObject(
                "SELECT id FROM stocks WHERE symbol = ?", Long.class, "MQ" + suffix.toUpperCase());
        jdbc.update("""
                INSERT INTO quote_events
                    (event_key, stock_id, price, event_time, source, sequence_no, processing_status, origin_type)
                VALUES (?, ?, 101.0000, NOW(6), 'MQ_IT', 1, 'PROCESSED', 'REAL')
                """, "mq-pipeline:" + RUN, stockId);
        long quoteId = jdbc.queryForObject(
                "SELECT id FROM quote_events WHERE event_key = ?", Long.class, "mq-pipeline:" + RUN);
        jdbc.update("""
                INSERT INTO alert_rules
                    (user_id, stock_id, name, direction, threshold_price, trigger_mode, status, version)
                VALUES (?, ?, 'MQ pipeline rule', 'ABOVE', 100.0000, 'ONCE', 'COMPLETED', 1)
                """, userId, stockId);
        long ruleId = jdbc.queryForObject(
                "SELECT id FROM alert_rules WHERE user_id = ? AND name = 'MQ pipeline rule'", Long.class, userId);
        String snapshot = """
                {"id":%d,"stockId":%d,"stockSymbol":"MQ%s","stockName":"Pipeline Stock",\
                "name":"MQ pipeline rule","direction":"ABOVE","thresholdPrice":100.0000,\
                "triggerMode":"ONCE","status":"ACTIVE","version":0}
                """.formatted(ruleId, stockId, suffix.toUpperCase()).replace("\n", "");
        jdbc.update("""
                INSERT INTO alert_triggers
                    (trigger_key, rule_id, quote_event_id, trigger_price, event_time, rule_snapshot)
                VALUES (?, ?, ?, 101.0000, NOW(6), ?)
                """, "mq-pipeline-trigger:" + RUN, ruleId, quoteId, snapshot);
        long triggerId = jdbc.queryForObject(
                "SELECT id FROM alert_triggers WHERE trigger_key = ?", Long.class, "mq-pipeline-trigger:" + RUN);
        String payload = """
                {"schemaVersion":1,"triggerId":%d,"userId":%d,"ruleId":%d,"stockId":%d}
                """.formatted(triggerId, userId, ruleId, stockId).trim();
        jdbc.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('ALERT_TRIGGER', ?, 'ALERT_TRIGGERED', ?)
                """, triggerId, payload);

        assertThat(publisher.publishBatch()).isEqualTo(1);
        awaitCount("SELECT COUNT(*) FROM notifications WHERE trigger_id = ?", triggerId, 1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = ?", String.class, triggerId))
                .isEqualTo("SENT");

        // This deliberately sends a duplicate broker message. The database unique key is the final guard.
        rocketMq.syncSend("price-pulse-notification-pipeline-it", payload);
        awaitCount("SELECT COUNT(*) FROM notifications WHERE trigger_id = ?", triggerId, 1);
        Thread.sleep(500);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE trigger_id = ?", Integer.class, triggerId))
                .isEqualTo(1);
    }

    private void awaitCount(String sql, long id, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject(sql, Integer.class, id) == expected) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(jdbc.queryForObject(sql, Integer.class, id)).isEqualTo(expected);
    }
}
