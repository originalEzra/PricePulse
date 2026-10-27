package com.ezra.pricepulse.performance;

import com.ezra.pricepulse.PricePulseApplication;
import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertRule;
import com.ezra.pricepulse.alert.AlertRuleMapper;
import com.ezra.pricepulse.alert.AlertRuleStatus;
import com.ezra.pricepulse.auth.AuthService;
import com.ezra.pricepulse.auth.RegisterRequest;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.market.MarketService;
import com.ezra.pricepulse.market.QuoteInputRequest;
import com.ezra.pricepulse.market.QuoteOutcome;
import com.ezra.pricepulse.market.QuoteResult;
import com.ezra.pricepulse.market.ReplayRequest;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.outbox.MessagingProperties;
import com.ezra.pricepulse.outbox.OutboxDispatchService;
import com.ezra.pricepulse.outbox.RocketMqOutboxPublisher;
import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PortBinding;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in experiments. Uses disposable MySQL/Redis and a run-specific topic/group on Compose MQ.
 * No timing thresholds: measured speed is evidence, not a pass/fail assertion about a laptop.
 */
@SpringBootTest(classes = PricePulseApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "price-pulse.security.jwt.secret=performance-only-synthetic-jwt-secret-at-least-32-bytes",
        "price-pulse.assistant.enabled=false",
        "price-pulse.messaging.enabled=true",
        "price-pulse.messaging.publisher-initial-delay-ms=3600000",
        "price-pulse.messaging.lease-duration=PT1S",
        "price-pulse.messaging.initial-backoff=PT0.1S",
        "price-pulse.messaging.max-backoff=PT0.5S",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=5000",
        "spring.data.redis.connect-timeout=1s",
        "spring.data.redis.timeout=1s",
        "rocketmq.producer.send-message-timeout=5000"
})
@ActiveProfiles("performance")
@Testcontainers
class PerformanceIT {
    private static final String RUN = UUID.randomUUID().toString();
    private static final String TOPIC = "price-pulse-perf-" + RUN.substring(0, 8);
    private static final long SEED = Long.getLong("perf.seed", 20261027L);
    private static final LocalDateTime EVENT_BASE = LocalDateTime.of(2024, 1, 1, 0, 0);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int REDIS_HOST_PORT = availablePort();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7")
            .withDatabaseName("price_pulse_performance")
            .withUrlParam("rewriteBatchedStatements", "true");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.6-alpine"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", REDIS_HOST_PORT),
                            new ExposedPort(6379))));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("price-pulse.messaging.topic", () -> TOPIC);
        registry.add("price-pulse.messaging.consumer-group", () -> "price-pulse-perf-consumer-" + RUN);
        registry.add("rocketmq.producer.group", () -> "price-pulse-perf-producer-" + RUN);
        registry.add("rocketmq.name-server",
                () -> System.getenv().getOrDefault("ROCKETMQ_NAMESERVER", "127.0.0.1:19876"));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StockMapper stocks;
    @Autowired AlertRuleMapper rules;
    @Autowired RuleIndex index;
    @Autowired MarketService market;
    @Autowired AuthService auth;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RocketMqOutboxPublisher publisher;
    @Autowired OutboxDispatchService dispatch;
    @Autowired MessagingProperties messaging;
    @Autowired RocketMQTemplate rocketMq;
    @Autowired ApplicationContext context;
    @Autowired MeterRegistry metrics;
    @LocalServerPort int port;

    private long userId;
    private String token;
    private TransactionTemplate transaction;
    private final Map<String, Object> results = new LinkedHashMap<>();

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void recordPerformanceAndRecoveryEvidence() throws Exception {
        int samples = boundedProperty("perf.samples", 150, 50, 1000);
        int burstSize = boundedProperty("perf.burst", 500, 10, 5000);
        int[] scales = Arrays.stream(System.getProperty("perf.scales", "1000,10000,50000").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        assertThat(scales.length).isBetween(1, 5);
        assertThat(Arrays.stream(scales).allMatch(size -> size >= 100 && size <= 200_000)).isTrue();
        transaction = new TransactionTemplate(transactions);
        var account = auth.register(new RegisterRequest(
                "performance-" + RUN + "@example.test", "Synthetic-test-password-123", "Performance Test"));
        userId = account.user().id();
        token = account.accessToken();
        results.put("runId", RUN);
        results.put("startedAt", Instant.now().toString());
        results.put("configuration", Map.of("seed", SEED, "scales", scales, "samples", samples,
                "burstSize", burstSize, "hikariConnections", 5, "leaseSeconds", 1,
                "initialRetryMs", 100, "consumerThreads", "2-4"));
        results.put("environment", EvidenceReport.environment());
        try {
            System.out.println("PERF: comparing full-fetch scan and Redis candidates");
            results.put("matching", compareMatching(scales, samples));
            EvidenceReport.write(json, results, "RUNNING");
            System.out.println("PERF: authenticated HTTP workload");
            results.put("http", httpWorkloads());
            EvidenceReport.write(json, results, "RUNNING");
            System.out.println("PERF: notification backlog and duplicate delivery");
            results.put("notificationBurst", notificationBurst(burstSize));
            EvidenceReport.write(json, results, "RUNNING");
            System.out.println("PERF: publication failure and lease recovery");
            results.put("publicationFailure", publicationFailure());
            results.put("publisherLeaseRecovery", publisherLeaseRecovery());
            System.out.println("PERF: isolated Redis stop/start and same-event retry");
            results.put("redisRecovery", redisRecovery());
            EvidenceReport.write(json, results, "PASSED");
        } catch (Throwable failure) {
            results.put("failureType", failure.getClass().getSimpleName());
            EvidenceReport.write(json, results, "FAILED");
            throw failure;
        }
    }

    private List<Map<String, Object>> compareMatching(int[] scales, int samples) {
        var report = new ArrayList<Map<String, Object>>();
        for (int size : scales) {
            long stockId = stock("INDEX" + size);
            seedIndexRules(stockId, size);
            var cold = new ArrayList<Double>();
            for (int attempt = 0; attempt < 3; attempt++) {
                transaction.executeWithoutResult(status -> {
                    var locked = stocks.findActiveByIdForUpdate(stockId);
                    redis.delete(RuleIndex.key(stockId));
                    long start = System.nanoTime();
                    index.candidates(locked, new BigDecimal("149.9"), new BigDecimal("150"));
                    cold.add(elapsedMs(start));
                });
            }
            var baseline = new ArrayList<Double>();
            var indexed = new ArrayList<Double>();
            var cardinalities = new ArrayList<Integer>();
            Random random = new Random(SEED);
            for (int sample = -10; sample < samples; sample++) {
                BigDecimal previous = new BigDecimal("140").add(BigDecimal.valueOf(random.nextInt(2000), 2));
                BigDecimal current = previous.add(new BigDecimal(sample % 2 == 0 ? "0.10" : "-0.10"));
                boolean measured = sample >= 0;
                boolean indexFirst = sample % 2 == 0;
                transaction.executeWithoutResult(status -> {
                    var locked = stocks.findActiveByIdForUpdate(stockId);
                    List<Long> expected;
                    List<Long> actual;
                    long start = System.nanoTime();
                    if (indexFirst) {
                        actual = index.candidates(locked, previous, current);
                        double indexMs = elapsedMs(start);
                        start = System.nanoTime();
                        expected = scan(stockId, previous, current);
                        if (measured) { indexed.add(indexMs); baseline.add(elapsedMs(start)); }
                    } else {
                        expected = scan(stockId, previous, current);
                        double scanMs = elapsedMs(start);
                        start = System.nanoTime();
                        actual = index.candidates(locked, previous, current);
                        if (measured) { baseline.add(scanMs); indexed.add(elapsedMs(start)); }
                    }
                    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
                    if (measured) cardinalities.add(actual.size());
                });
            }
            report.add(Map.of("rules", size, "warmupPairs", 10,
                    "fullFetchScan", EvidenceReport.stats(baseline),
                    "redisLookup", EvidenceReport.stats(indexed), "coldRebuildMs", cold,
                    "meanCandidates", cardinalities.stream().mapToInt(Integer::intValue).average().orElseThrow(),
                    "candidateSetMismatches", 0));
            System.out.println("PERF: matched scale " + size);
        }
        return report;
    }

    private List<Long> scan(long stockId, BigDecimal previous, BigDecimal current) {
        boolean rising = current.compareTo(previous) > 0;
        return rules.listIndexable(stockId).stream().filter(rule -> {
            boolean active = rule.getStatus() == AlertRuleStatus.ACTIVE;
            boolean above = rule.getDirection() == AlertDirection.ABOVE;
            if (rising != (active == above)) return false;
            BigDecimal boundary = active ? rule.getThresholdPrice() : rule.getRearmPrice();
            return rising
                    ? boundary.compareTo(previous) > 0 && boundary.compareTo(current) <= 0
                    : boundary.compareTo(current) >= 0 && boundary.compareTo(previous) < 0;
        }).map(AlertRule::getId).toList();
    }

    private void seedIndexRules(long stockId, int count) {
        Random random = new Random(SEED);
        var rows = new ArrayList<Object[]>(count);
        for (int i = 0; i < count; i++) {
            int band = i % 4;
            boolean cooldown = band >= 2;
            BigDecimal boundary = HUNDRED.add(BigDecimal.valueOf(random.nextInt(10_000), 2));
            BigDecimal threshold = band == 2 ? boundary.add(BigDecimal.valueOf(5))
                    : band == 3 ? boundary.subtract(BigDecimal.valueOf(5)) : boundary;
            rows.add(new Object[] {userId, stockId, "Index fixture " + i,
                    band % 2 == 0 ? "ABOVE" : "BELOW", threshold,
                    cooldown ? "REPEAT" : "ONCE", cooldown ? boundary : null,
                    cooldown ? "COOLDOWN" : "ACTIVE"});
        }
        transaction.executeWithoutResult(status -> jdbc.batchUpdate("""
                INSERT INTO alert_rules
                (user_id, stock_id, name, direction, threshold_price, trigger_mode, rearm_price, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, 500, (statement, row) -> {
                    for (int field = 0; field < row.length; field++) statement.setObject(field + 1, row[field]);
                }));
    }

    private List<Map<String, Object>> httpWorkloads() throws Exception {
        var cases = new ArrayList<Map<String, Object>>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int actors : List.of(1, 4)) {
                var ids = new ArrayList<Long>();
                for (int i = 0; i < actors; i++) {
                    long id = stock("HTTP" + actors + "_" + i);
                    seedOnceRules(id, 1000, new BigDecimal("1000"));
                    quote(id, 0, "95");
                    ids.add(id);
                    for (int warm = 1; warm <= 5; warm++) httpQuote(client, id, warm);
                }
                var latencies = new ArrayList<Double>();
                CountDownLatch start = new CountDownLatch(1);
                long wallStart = System.nanoTime();
                try (var pool = Executors.newFixedThreadPool(actors)) {
                    var futures = ids.stream().map(id -> pool.submit(() -> {
                        start.await();
                        var times = new ArrayList<Double>();
                        for (int step = 6; step <= 105; step++) times.add(httpQuote(client, id, step));
                        return times;
                    })).toList();
                    start.countDown();
                    for (var future : futures) latencies.addAll(future.get(90, TimeUnit.SECONDS));
                }
                double seconds = (System.nanoTime() - wallStart) / 1_000_000_000.0;
                int persisted = ids.stream().mapToInt(id -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM quote_events WHERE stock_id = ? AND processing_status = 'PROCESSED'",
                        Integer.class, id)).sum();
                assertThat(persisted).isEqualTo(actors * 106);
                cases.add(Map.of("actors", actors, "stocks", actors, "rulesPerStock", 1000,
                        "measuredRequests", latencies.size(), "warmupRequests", actors * 5,
                        "latency", EvidenceReport.stats(latencies), "wallSeconds", seconds,
                        "requestsPerSecond", latencies.size() / seconds, "errors", 0,
                        "workload", "one ordered stream per stock; no threshold crossings"));
            }
        }
        return cases;
    }

    private double httpQuote(HttpClient client, long stockId, int step) throws Exception {
        var input = event(stockId, step, step % 2 == 0 ? "95.1" : "95");
        String body = json.writeValueAsString(new ReplayRequest(List.of(input)));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/market/replay"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long start = System.nanoTime();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        double ms = elapsedMs(start);
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.path("success").asBoolean()).isTrue();
        assertThat(data.path("data").path("results").get(0).path("outcome").asText()).isEqualTo("PROCESSED");
        return ms;
    }

    private Map<String, Object> notificationBurst(int count) throws Exception {
        long stockId = stock("BURST");
        seedOnceRules(stockId, count, HUNDRED);
        quote(stockId, 0, "95");
        var consumer = consumerContainer();
        consumer.getConsumer().suspend();
        long firstDelivery = deliveries();
        double triggerMs;
        double publishMs;
        try {
            long start = System.nanoTime();
            assertThat(quote(stockId, 1, "101").matching().triggered()).isEqualTo(count);
            triggerMs = elapsedMs(start);
            assertThat(outstanding(stockId)).isEqualTo(count);
            start = System.nanoTime();
            publishAll();
            publishMs = elapsedMs(start);
            assertThat(notificationCount(stockId)).isZero();
        } finally {
            consumer.getConsumer().resume();
        }
        long resume = System.nanoTime();
        await(() -> notificationCount(stockId) == count && deliveries() >= firstDelivery + count,
                Duration.ofSeconds(60));
        double drainMs = elapsedMs(resume);
        List<Double> endToEnd = jdbc.queryForList("""
                SELECT TIMESTAMPDIFF(MICROSECOND, t.created_at, n.created_at) / 1000.0
                FROM notifications n JOIN alert_triggers t ON t.id = n.trigger_id
                JOIN alert_rules r ON r.id = t.rule_id WHERE r.stock_id = ?
                """, Double.class, stockId);
        assertThat(endToEnd).hasSize(count).allMatch(ms -> ms >= 0);
        long beforeDuplicate = deliveries();
        List<String> duplicates = payloads(stockId).stream().limit(10).toList();
        for (String payload : duplicates) {
            assertThat(rocketMq.syncSend(TOPIC, payload).getSendStatus()).isEqualTo(SendStatus.SEND_OK);
        }
        await(() -> deliveries() >= beforeDuplicate + duplicates.size(), Duration.ofSeconds(30));
        assertThat(notificationCount(stockId)).isEqualTo(count);
        return Map.of("rulesTriggered", count, "triggerTransactionMs", triggerMs,
                "backlogPeakJobs", count, "publishWhilePausedMs", publishMs,
                "resumeToDrainedMs", drainMs, "drainNotificationsPerSecond", count * 1000.0 / drainMs,
                "triggerToNotificationLatency", EvidenceReport.stats(endToEnd),
                "extraBrokerDeliveries", duplicates.size(), "duplicateBusinessNotifications", 0,
                "missingNotifications", 0);
    }

    private Map<String, Object> publicationFailure() throws Exception {
        long stockId = stock("SENDFAIL");
        seedOnceRules(stockId, 2, HUNDRED);
        quote(stockId, 0, "95");
        quote(stockId, 1, "101");
        RocketMQTemplate failing = mock(RocketMQTemplate.class);
        when(failing.syncSend(anyString(), org.mockito.ArgumentMatchers.any(
                org.springframework.messaging.Message.class))).thenThrow(new IllegalStateException("injected send failure"));
        var unavailablePublisher = new RocketMqOutboxPublisher(dispatch, failing, messaging);
        assertThat(unavailablePublisher.publishBatch()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED'", Integer.class)).isEqualTo(2);
        assertThat(notificationCount(stockId)).isZero();
        long start = System.nanoTime();
        await(() -> {
            publisher.publishAvailable();
            return notificationCount(stockId) == 2;
        }, Duration.ofSeconds(30));
        assertThat(outstanding(stockId)).isZero();
        return Map.of("fault", "injected producer transport exception; shared broker remains running",
                "durableFailedTasks", 2, "recoveredNotifications", 2, "recoveryMs", elapsedMs(start));
    }

    private Map<String, Object> publisherLeaseRecovery() throws Exception {
        long stockId = stock("LEASE");
        seedOnceRules(stockId, 1, HUNDRED);
        quote(stockId, 0, "95");
        quote(stockId, 1, "101");
        var claimed = dispatch.claimBatch();
        assertThat(claimed).hasSize(1);
        var event = claimed.getFirst();
        long before = deliveries();
        long start = System.nanoTime();
        assertThat(rocketMq.syncSend(TOPIC, event.getPayload()).getSendStatus()).isEqualTo(SendStatus.SEND_OK);
        // Deliberately omit markPublished: reproduces an acknowledged-send / application-crash window.
        await(() -> notificationCount(stockId) == 1 && deliveries() >= before + 1, Duration.ofSeconds(30));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, event.getId())).isEqualTo("SENDING");
        await(() -> {
            publisher.publishAvailable();
            return deliveries() >= before + 2;
        }, Duration.ofSeconds(30));
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, event.getId())).isEqualTo(2);
        assertThat(notificationCount(stockId)).isEqualTo(1);
        assertThat(outstanding(stockId)).isZero();
        return Map.of("fault", "skip Outbox acknowledgement after real Broker SEND_OK; no process kill",
                "leaseSeconds", 1, "brokerDeliveries", 2, "businessNotifications", 1,
                "missingNotifications", 0, "recoveryMs", elapsedMs(start));
    }

    private Map<String, Object> redisRecovery() throws Exception {
        long stockId = stock("REDISFAIL");
        seedOnceRules(stockId, 1, HUNDRED);
        quote(stockId, 0, "95");
        String eventKey = event(stockId, 1, "101").eventKey();
        long down = System.nanoTime();
        try {
            REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(1).exec();
            assertThatThrownBy(() -> quote(stockId, 1, "101"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quote_events WHERE event_key = ?", Integer.class, eventKey)).isZero();
            assertThat(stocks.findActiveById(stockId).latestPrice()).isEqualByComparingTo("95");
            assertThat(outstanding(stockId)).isZero();
        } finally {
            REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        }
        var binding = REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(new ExposedPort(6379))[0];
        assertThat(binding.getHostPortSpec()).isEqualTo(Integer.toString(REDIS_HOST_PORT));
        assertThat(REDIS.execInContainer("redis-cli", "PING").getStdout()).contains("PONG");
        await(() -> {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                return "PONG".equals(connection.ping());
            } catch (RuntimeException unavailable) { return false; }
        }, Duration.ofSeconds(30));
        assertThat(quote(stockId, 1, "101").matching().triggered()).isEqualTo(1);
        assertThat(quote(stockId, 1, "101").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        publishAll();
        await(() -> notificationCount(stockId) == 1, Duration.ofSeconds(30));
        return Map.of("fault", "stop and restart only the disposable Redis container",
                "failedQuoteRolledBack", true, "sameEventKeyRetryTriggers", 1,
                "duplicateRetryOutcome", "DUPLICATE", "businessNotifications", 1,
                "stopToNotificationMs", elapsedMs(down));
    }

    private DefaultRocketMQListenerContainer consumerContainer() {
        return context.getBeansOfType(DefaultRocketMQListenerContainer.class).values().stream()
                .filter(container -> container.getTopic().equals(TOPIC)).findFirst().orElseThrow();
    }

    private long deliveries() {
        return (long) metrics.get("pricepulse.notification.deliveries").tag("outcome", "success").counter().count();
    }

    private long stock(String label) {
        String symbol = label + "_" + RUN.substring(0, 8);
        jdbc.update("INSERT INTO stocks (symbol, name, market, currency) VALUES (?, ?, 'SIM', 'USD')",
                symbol, "Performance fixture " + label);
        return jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long.class, symbol);
    }

    private void seedOnceRules(long stockId, int count, BigDecimal threshold) {
        var positions = java.util.stream.IntStream.range(0, count).boxed().toList();
        transaction.executeWithoutResult(status -> jdbc.batchUpdate("""
                INSERT INTO alert_rules (user_id, stock_id, name, direction, threshold_price, trigger_mode)
                VALUES (?, ?, ?, 'ABOVE', ?, 'ONCE')
                """, positions, 500, (statement, position) -> {
                    statement.setLong(1, userId);
                    statement.setLong(2, stockId);
                    statement.setString(3, "Performance rule " + position);
                    statement.setBigDecimal(4, threshold);
                }));
    }

    private QuoteInputRequest event(long stockId, int step, String price) {
        return new QuoteInputRequest("perf:" + RUN + ":" + stockId + ":" + step, stockId,
                new BigDecimal(price), EVENT_BASE.plusSeconds(step), "PERFORMANCE", (long) step);
    }

    private QuoteResult quote(long stockId, int step, String price) {
        return market.replay(new ReplayRequest(List.of(event(stockId, step, price)))).results().getFirst();
    }

    private int notificationCount(long stockId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN alert_triggers t ON t.id = n.trigger_id
                JOIN alert_rules r ON r.id = t.rule_id WHERE r.stock_id = ?
                """, Integer.class, stockId);
    }

    private int outstanding(long stockId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o JOIN alert_triggers t ON t.id = o.aggregate_id
                JOIN alert_rules r ON r.id = t.rule_id WHERE r.stock_id = ? AND o.status != 'SENT'
                """, Integer.class, stockId);
    }

    private List<String> payloads(long stockId) {
        return jdbc.queryForList("""
                SELECT o.payload FROM outbox_events o JOIN alert_triggers t ON t.id = o.aggregate_id
                JOIN alert_rules r ON r.id = t.rule_id WHERE r.stock_id = ? ORDER BY o.id
                """, String.class, stockId);
    }

    private void publishAll() {
        await(() -> {
            publisher.publishAvailable();
            return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status != 'SENT'",
                    Integer.class) == 0;
        }, Duration.ofSeconds(60));
    }

    private void await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        assertThat(condition.getAsBoolean()).as("condition within " + timeout).isTrue();
    }

    private double elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000.0; }

    private int boundedProperty(String name, int defaultValue, int min, int max) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be in [" + min + "," + max + "]");
        return value;
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot reserve a local test port", failure);
        }
    }
}
