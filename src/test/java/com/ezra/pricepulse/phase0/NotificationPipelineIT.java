package com.ezra.pricepulse.phase0;

import static org.assertj.core.api.Assertions.assertThat;

import com.ezra.pricepulse.PricePulseApplication;
import com.ezra.pricepulse.outbox.RocketMqOutboxPublisher;
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
        "price-pulse.messaging.publisher-initial-delay-ms=600000",
        "rocketmq.name-server=${ROCKETMQ_NAMESERVER:127.0.0.1:19876}"
})
@ActiveProfiles("mq-pipeline-it")
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
                    (event_key, stock_id, price, event_time, source, sequence_no, processing_status)
                VALUES (?, ?, 101.0000, NOW(6), 'MQ_IT', 1, 'PROCESSED')
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
