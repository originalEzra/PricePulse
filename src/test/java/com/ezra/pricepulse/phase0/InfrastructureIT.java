package com.ezra.pricepulse.phase0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ezra.pricepulse.phase0.persistence.ProbeMapper;
import com.ezra.pricepulse.market.MarketService;
import com.ezra.pricepulse.market.QuoteInputRequest;
import com.ezra.pricepulse.market.ReplayRequest;
import com.ezra.pricepulse.notification.NotificationService;
import com.ezra.pricepulse.outbox.OutboxDispatchService;
import com.ezra.pricepulse.outbox.OutboxEvent;
import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@org.springframework.context.annotation.Import(com.ezra.pricepulse.market.RealQuoteTestSupport.class)
@SpringBootTest
@ActiveProfiles("integration")
@Testcontainers
@AutoConfigureMockMvc
class InfrastructureIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7")
            .withDatabaseName("price_pulse_test");
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
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/local");
        registry.add("price-pulse.security.jwt.secret",
                () -> "price-pulse-integration-jwt-secret-at-least-32-bytes");
    }

    @Autowired ProbeMapper mapper;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.ezra.pricepulse.market.RealQuoteTestSupport.Fixture realFixture;
    @Autowired NotificationService notificationService;
    @Autowired OutboxDispatchService outboxDispatchService;
    @Autowired StockMapper stockMapper;

    @Test
    void assistantRequiresAuthenticationAndIsExplicitlyUnavailableWhenDisabled() throws Exception {
        mvc.perform(post("/api/assistant/alert-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "DEMO001 上破 100 提醒一次"))))
                .andExpect(status().isUnauthorized());

        String token = matchingToken(testSuffix());
        mvc.perform(post("/api/assistant/alert-drafts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "DEMO001 上破 100 提醒一次"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        mvc.perform(post("/api/assistant/alert-drafts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", "  "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assistantStockLookupHandlesDuplicateSymbolsAcrossMarkets() {
        String symbol = "AI" + testSuffix();
        long stockId = insertStock(symbol);
        jdbc.update("""
                INSERT INTO stocks (symbol, name, market, currency, status)
                VALUES (?, 'Other market stock', 'TEST', 'USD', 'ACTIVE')
                """, symbol);

        assertThat(stockMapper.findActiveBySymbol(symbol, null)).hasSize(2);
        assertThat(stockMapper.findActiveBySymbol(symbol, "SIM"))
                .singleElement().satisfies(stock -> assertThat(stock.id()).isEqualTo(stockId));
        assertThat(stockMapper.findActiveBySymbol(symbol, "UNKNOWN")).isEmpty();
    }

    @Test
    void assistantNameAndFragmentLookupRespectsMarketsAndActiveStatus() {
        String marker = "REF" + testSuffix();
        jdbc.update("""
                INSERT INTO stocks (symbol, name, market, currency, status)
                VALUES (?, ?, 'SIM', 'USD', 'ACTIVE'),
                       (?, ?, 'OTHER', 'USD', 'ACTIVE'),
                       (?, ?, 'SIM', 'USD', 'INACTIVE')
                """, marker + "001", marker + " Technology",
                marker + "002", marker + " Consumer",
                marker + "003", marker + " Hidden");

        assertThat(stockMapper.findActiveByName(marker.toLowerCase() + " technology", null))
                .singleElement().satisfies(stock -> assertThat(stock.symbol()).isEqualTo(marker + "001"));
        assertThat(stockMapper.findActiveByFragment("%" + marker + "%", null)).hasSize(2);
        assertThat(stockMapper.findActiveByFragment("%" + marker.toLowerCase() + "%", "SIM"))
                .singleElement().satisfies(stock -> assertThat(stock.symbol()).isEqualTo(marker + "001"));
        assertThat(stockMapper.findActiveByFragment("%" + marker + "001%", null))
                .singleElement().satisfies(stock -> assertThat(stock.name()).endsWith("Technology"));
        assertThat(stockMapper.findActiveByFragment("%" + marker + "%", "UNKNOWN")).isEmpty();
    }

    @Test
    void assistantFragmentLookupEscapesLiteralWildcardCharacters() {
        String marker = "LIT" + testSuffix();
        jdbc.update("""
                INSERT INTO stocks (symbol, name, market, currency, status)
                VALUES (?, ?, 'SIM', 'USD', 'ACTIVE'), (?, ?, 'SIM', 'USD', 'ACTIVE')
                """, marker + "1", marker + "%_! Holdings", marker + "2", marker + "abc! Holdings");

        assertThat(stockMapper.findActiveByFragment("%" + marker + "!%!_!!%", null))
                .singleElement().satisfies(stock -> assertThat(stock.symbol()).isEqualTo(marker + "1"));
    }

    @Test
    void assistantFragmentLookupBoundsTheCandidateList() {
        String marker = "MANY" + testSuffix();
        for (int index = 0; index < 7; index++) {
            jdbc.update("""
                    INSERT INTO stocks (symbol, name, market, currency, status)
                    VALUES (?, ?, 'SIM', 'USD', 'ACTIVE')
                    """, marker + index, marker + " candidate " + index);
        }

        assertThat(stockMapper.findActiveByFragment("%" + marker + "%", null)).hasSize(6);
    }

    @Test
    void flywayAndMybatisRoundTripAgainstRealMysql() {
        String id = UUID.randomUUID().toString();
        try {
            assertThat(mapper.insert(id, "phase0-ok")).isEqualTo(1);
            assertThat(mapper.findPayload(id)).isEqualTo("phase0-ok");
        } finally {
            mapper.delete(id);
        }
    }

    @Test
    void redisZsetRangeUsesExclusivePreviousAndInclusiveCurrentPrice() {
        String key = "pricepulse:phase0:" + UUID.randomUUID();
        try {
            redis.opsForZSet().add(key, "previous-boundary", 9500);
            redis.expire(key, Duration.ofMinutes(1));
            redis.opsForZSet().add(key, "crossed", 10000);
            redis.opsForZSet().add(key, "current-boundary", 10500);
            redis.opsForZSet().add(key, "outside", 10501);
            // Prices are integer ticks. (9500, 10500] is [9501, 10500].
            assertThat(redis.opsForZSet().rangeByScore(key, 9501, 10500))
                    .containsExactly("crossed", "current-boundary");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void flywayCreatesTheSevenPhaseOneBusinessTables() {
        var tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'users', 'stocks', 'quote_events', 'alert_rules',
                    'alert_triggers', 'outbox_events', 'notifications'
                  )
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "users", "stocks", "quote_events", "alert_rules",
                "alert_triggers", "outbox_events", "notifications");

        var expectedColumnCounts = Map.of(
                "users", 7,
                "stocks", 11,
                "quote_events", 17,
                "alert_rules", 14,
                "alert_triggers", 8,
                "outbox_events", 13,
                "notifications", 9);
        expectedColumnCounts.forEach((table, expectedCount) -> {
            Integer actualCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = ?
                    """, Integer.class, table);
            assertThat(actualCount).as("column count for %s", table).isEqualTo(expectedCount);
        });

        var outboxColumns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'outbox_events'
                """, String.class);
        assertThat(outboxColumns).contains("status", "attempts", "next_retry_at", "locked_at", "locked_by");
        assertThat(jdbc.queryForList(
                "SELECT symbol FROM stocks WHERE symbol LIKE 'DEMO%' ORDER BY symbol", String.class))
                .containsExactly("DEMO001", "DEMO002", "DEMO003");
    }

    @Test
    void authenticationAndOwnedRuleLifecycleWorkEndToEnd() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String emailA = "alice-" + suffix + "@example.com";
        String emailB = "bob-" + suffix + "@example.com";
        String tokenA = register(emailA, "Alice Test", "password-A-123");
        String tokenB = register(emailB, "Bob Test", "password-B-123");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", emailA.toUpperCase(),
                                "displayName", "Duplicate Alice",
                                "password", "password-C-123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", emailA, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", emailA, "password", "password-A-123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, emailA);
        assertThat(passwordHash).startsWith("$2").doesNotContain("password-A-123");

        String symbol = "T" + suffix.toUpperCase();
        jdbc.update("""
                INSERT INTO stocks (symbol, name, market, currency, latest_price, status)
                VALUES (?, 'Test Stock', 'SIM', 'USD', 95.0000, 'ACTIVE')
                """, symbol);
        Long stockId = jdbc.queryForObject(
                "SELECT id FROM stocks WHERE market = 'SIM' AND symbol = ?", Long.class, symbol);

        mvc.perform(get("/api/stocks").param("query", symbol))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].symbol").value(symbol));

        String createBody = json(Map.of(
                "stockId", stockId,
                "name", "Break above 100",
                "direction", "ABOVE",
                "thresholdPrice", 100,
                "triggerMode", "ONCE"));

        mvc.perform(post("/api/alert-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        String createdJson = mvc.perform(post("/api/alert-rules")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long ruleId = objectMapper.readTree(createdJson).path("data").path("id").asLong();

        mvc.perform(get("/api/alert-rules/{ruleId}", ruleId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(post("/api/alert-rules")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "stockId", stockId,
                                "name", "Invalid repeat rule",
                                "direction", "ABOVE",
                                "thresholdPrice", 100,
                                "triggerMode", "REPEAT",
                                "rearmPrice", 101))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));

        mvc.perform(post("/api/alert-rules/{ruleId}/pause", ruleId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(post("/api/alert-rules/{ruleId}/resume", ruleId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        mvc.perform(post("/api/alert-rules/{ruleId}/resume", ruleId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(2));

        Map<String, Object> replacement = Map.of(
                "stockId", stockId,
                "name", "Break above 105",
                "direction", "ABOVE",
                "thresholdPrice", 105,
                "triggerMode", "ONCE");
        mvc.perform(put("/api/alert-rules/{ruleId}", ruleId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 2, "rule", replacement))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thresholdPrice").value(105.0000))
                .andExpect(jsonPath("$.data.version").value(3));

        mvc.perform(delete("/api/alert-rules/{ruleId}", ruleId)
                        .header("Authorization", "Bearer " + tokenA)
                        .param("version", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedRuleId").value(ruleId));

        mvc.perform(get("/api/alert-rules/{ruleId}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/alert-rules")
                        .header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revocation")
                        .value("The token remains valid until it expires"));

        mvc.perform(get("/api/alert-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void marketIngestionReplayAndSimulationFollowThePhaseThreePolicies() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String token = register(
                "market-" + suffix.toLowerCase() + "@example.com",
                "Market Test",
                "market-password-123");
        long liveStockId = insertStock("L" + suffix);
        long replayStockId = insertStock("R" + suffix);
        long simulationStockId = insertStock("S" + suffix);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(123_000_000);

        mvc.perform(post("/test/real-quotes/live")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventKey", "live-unauthorized-" + suffix,
                                "stockId", liveStockId,
                                "price", 100,
                                "eventTime", now,
                                "source", "TEST"))))
                .andExpect(status().isUnauthorized());

        Map<String, Object> liveEvent = Map.of(
                "eventKey", "live-accepted-" + suffix,
                "stockId", liveStockId,
                "price", 101.2500,
                "eventTime", now.minusSeconds(10),
                "source", "TEST",
                "sequenceNo", 1);
        mvc.perform(post("/test/real-quotes/live")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(liveEvent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"))
                .andExpect(jsonPath("$.data.previousPrice").doesNotExist());

        mvc.perform(post("/test/real-quotes/live")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(liveEvent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));

        mvc.perform(post("/test/real-quotes/live")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventKey", "live-accepted-" + suffix,
                                "stockId", liveStockId,
                                "price", 102,
                                "eventTime", now.minusSeconds(10),
                                "source", "TEST",
                                "sequenceNo", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        mvc.perform(post("/test/real-quotes/live")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventKey", "live-stale-" + suffix,
                                "stockId", liveStockId,
                                "price", 90,
                                "eventTime", now.minusMinutes(10),
                                "source", "TEST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED_STALE"));

        mvc.perform(post("/test/real-quotes/live")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventKey", "live-future-" + suffix,
                                "stockId", liveStockId,
                                "price", 110,
                                "eventTime", now.plusMinutes(1),
                                "source", "TEST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED_FUTURE"));

        mvc.perform(post("/test/real-quotes/live")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "eventKey", "live-out-of-order-" + suffix,
                                "stockId", liveStockId,
                                "price", 99,
                                "eventTime", now.minusSeconds(11),
                                "source", "TEST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("DISCARDED_OUT_OF_ORDER"));

        assertThat(jdbc.queryForObject(
                "SELECT latest_price FROM stocks WHERE id = ?", BigDecimal.class, liveStockId))
                .isEqualByComparingTo("101.2500");

        List<Map<String, Object>> replayEvents = List.of(
                Map.of(
                        "eventKey", "replay-1-" + suffix,
                        "stockId", replayStockId,
                        "price", 50,
                        "eventTime", LocalDateTime.of(2024, 1, 1, 9, 0),
                        "source", "IGNORED",
                        "sequenceNo", 1),
                Map.of(
                        "eventKey", "replay-2-" + suffix,
                        "stockId", replayStockId,
                        "price", 52,
                        "eventTime", LocalDateTime.of(2024, 1, 1, 9, 1),
                        "source", "IGNORED",
                        "sequenceNo", 2),
                Map.of(
                        "eventKey", "replay-3-" + suffix,
                        "stockId", replayStockId,
                        "price", 51,
                        "eventTime", LocalDateTime.of(2024, 1, 1, 9, 0, 30),
                        "source", "IGNORED",
                        "sequenceNo", 3));
        mvc.perform(post("/test/real-quotes/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("events", replayEvents))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requested").value(3))
                .andExpect(jsonPath("$.data.processed").value(2))
                .andExpect(jsonPath("$.data.discarded").value(1))
                .andExpect(jsonPath("$.data.results[2].outcome")
                        .value("DISCARDED_OUT_OF_ORDER"));

        mvc.perform(post("/api/market/simulate").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/market/stocks/{stockId}/quotes", liveStockId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[?(@.processingStatus == 'PROCESSED')]").exists())
                .andExpect(jsonPath("$.data[?(@.discardReason == 'STALE')]").exists())
                .andExpect(jsonPath("$.data[?(@.discardReason == 'FUTURE')]").exists())
                .andExpect(jsonPath("$.data[?(@.discardReason == 'OUT_OF_ORDER')]").exists());
    }

    @Test
    void crossingBoundariesCreateOneTimeTriggersAndPendingOutboxAtomically() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        String outsider = matchingToken(testSuffix());
        long stockId = insertStock("C" + suffix);
        long aboveId = createMatchingRule(token, stockId, "ABOVE", "100", "ONCE", null);
        long oldUpperBoundaryId = createMatchingRule(token, stockId, "ABOVE", "95", "ONCE", null);
        long outsideRangeId = createMatchingRule(token, stockId, "ABOVE", "1000", "ONCE", null);
        long belowId = createMatchingRule(token, stockId, "BELOW", "90", "ONCE", null);

        // A first quote is a baseline, even when already beyond an active threshold.
        assertThat(replayQuote(token, stockId, suffix, 1, "105").path("matching").path("triggered").asInt())
                .isZero();
        assertThat(replayQuote(token, stockId, suffix, 2, "95").path("matching").path("triggered").asInt())
                .isZero();
        JsonNode exactUpperBoundary = replayQuote(token, stockId, suffix, 3, "100").path("matching");
        assertThat(exactUpperBoundary.path("triggered").asInt()).isEqualTo(1);
        assertThat(exactUpperBoundary.path("candidates").asInt()).isEqualTo(1);
        assertThat(ruleStatus(oldUpperBoundaryId)).isEqualTo("ACTIVE");
        assertThat(ruleStatus(outsideRangeId)).isEqualTo("ACTIVE");
        assertThat(ruleStatus(aboveId)).isEqualTo("COMPLETED");
        assertThat(replayQuote(token, stockId, suffix, 4, "100").path("matching").path("triggered").asInt())
                .isZero();
        assertThat(replayQuote(token, stockId, suffix, 3, "100").path("outcome").asText())
                .isEqualTo("DUPLICATE");
        long oldLowerBoundaryId = createMatchingRule(token, stockId, "BELOW", "100", "ONCE", null);
        assertThat(replayQuote(token, stockId, suffix, 5, "90").path("matching").path("triggered").asInt())
                .isEqualTo(1);
        assertThat(ruleStatus(belowId)).isEqualTo("COMPLETED");
        assertThat(ruleStatus(oldLowerBoundaryId)).isEqualTo("ACTIVE");

        assertThat(triggerCount(aboveId)).isEqualTo(1);
        assertThat(triggerCount(belowId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o
                JOIN alert_triggers t ON t.id = o.aggregate_id
                WHERE t.rule_id IN (?, ?) AND o.status = 'PENDING'
                """, Integer.class, aboveId, belowId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT trigger_key FROM alert_triggers WHERE rule_id = ?", String.class, aboveId))
                .isEqualTo("rule:" + aboveId + ":version:0");
        assertThat(jdbc.queryForObject(
                "SELECT JSON_VALID(rule_snapshot) FROM alert_triggers WHERE rule_id = ?",
                Integer.class, aboveId)).isEqualTo(1);

        mvc.perform(get("/api/alert-rules/{id}/triggers", aboveId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/alert-rules/{id}/triggers", aboveId)
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void repeatRulesWaitForInclusiveRearmPricesInBothDirections() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long aboveStock = insertStock("A" + suffix);
        long belowStock = insertStock("B" + suffix);
        long aboveId = createMatchingRule(token, aboveStock, "ABOVE", "100", "REPEAT", "95");
        long belowId = createMatchingRule(token, belowStock, "BELOW", "100", "REPEAT", "105");

        replayQuote(token, aboveStock, suffix, 1, "94");
        replayQuote(token, aboveStock, suffix, 2, "100");
        assertThat(ruleStatus(aboveId)).isEqualTo("COOLDOWN");
        replayQuote(token, aboveStock, suffix, 3, "99");
        replayQuote(token, aboveStock, suffix, 4, "101");
        assertThat(triggerCount(aboveId)).isEqualTo(1);
        assertThat(replayQuote(token, aboveStock, suffix, 5, "95").path("matching").path("rearmed").asInt())
                .isEqualTo(1);
        assertThat(ruleStatus(aboveId)).isEqualTo("ACTIVE");
        replayQuote(token, aboveStock, suffix, 6, "100");
        assertThat(triggerCount(aboveId)).isEqualTo(2);

        // Pausing a cooling rule is supported; resume explicitly starts a fresh active cycle.
        long version = ruleVersion(aboveId);
        mvc.perform(post("/api/alert-rules/{id}/pause", aboveId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", version))))
                .andExpect(status().isOk());
        replayQuote(token, aboveStock, suffix, 7, "94");
        mvc.perform(post("/api/alert-rules/{id}/resume", aboveId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", version + 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        replayQuote(token, aboveStock, suffix, 8, "100");
        assertThat(triggerCount(aboveId)).isEqualTo(3);

        replayQuote(token, belowStock, suffix, 1, "106");
        replayQuote(token, belowStock, suffix, 2, "100");
        assertThat(ruleStatus(belowId)).isEqualTo("COOLDOWN");
        replayQuote(token, belowStock, suffix, 3, "104");
        replayQuote(token, belowStock, suffix, 4, "99");
        assertThat(triggerCount(belowId)).isEqualTo(1);
        assertThat(replayQuote(token, belowStock, suffix, 5, "105").path("matching").path("rearmed").asInt())
                .isEqualTo(1);
        replayQuote(token, belowStock, suffix, 6, "100");
        assertThat(triggerCount(belowId)).isEqualTo(2);
    }

    @Test
    void observedReplayReportsRealTriggerCooldownAndRearmTransitionsWithoutReplayingDuplicates() throws Exception {
        String token = matchingToken(testSuffix());
        for (String direction : List.of("ABOVE", "BELOW")) {
            String suffix = testSuffix();
            long stockId = insertStock("TRACE" + suffix);
            long ruleId = createMatchingRule(token, stockId, direction, "100", "REPEAT",
                    direction.equals("ABOVE") ? "95" : "105");
            List<String> prices = direction.equals("ABOVE")
                    ? List.of("99", "100", "99", "95", "110")
                    : List.of("101", "100", "101", "105", "90");
            var events = new java.util.ArrayList<Map<String, Object>>();
            for (int index = 0; index < prices.size(); index++) {
                events.add(matchingEvent(stockId, suffix, index + 1, prices.get(index)));
            }
            String request = json(Map.of("ruleId", ruleId, "events", events));
            String body = mvc.perform(post("/test/real-quotes/replay")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode points = objectMapper.readTree(body).path("data").path("results");
            assertThat(points).hasSize(5);
            List<String> expected = List.of("NONE", "TRIGGERED", "NONE", "REARMED", "TRIGGERED");
            for (int index = 0; index < points.size(); index++) {
                JsonNode point = points.get(index);
                assertThat(point.path("observation").path("event").asText()).isEqualTo(expected.get(index));
                assertThat(point.path("observation").path("ruleId").asLong()).isEqualTo(ruleId);
                assertThat(point.path("observation").path("direction").asText()).isEqualTo(direction);
                if (expected.get(index).equals("TRIGGERED")) {
                    long triggerId = point.path("observation").path("triggerId").asLong();
                    assertThat(triggerId).isPositive();
                    assertThat(jdbc.queryForObject(
                            "SELECT quote_event_id FROM alert_triggers WHERE id = ?", Long.class, triggerId))
                            .isEqualTo(point.path("eventId").asLong());
                }
            }
            assertThat(points.get(1).path("observation").path("afterStatus").asText()).isEqualTo("COOLDOWN");
            assertThat(points.get(2).path("observation").path("afterStatus").asText()).isEqualTo("COOLDOWN");
            assertThat(points.get(3).path("observation").path("afterStatus").asText()).isEqualTo("ACTIVE");
            mvc.perform(post("/test/real-quotes/replay").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.duplicates").value(5))
                    .andExpect(jsonPath("$.data.results[0].observation").isEmpty());
            assertThat(triggerCount(ruleId)).isEqualTo(2);
            mvc.perform(post("/test/real-quotes/replay").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("ruleId", ruleId, "events", List.of(matchingEvent(stockId, suffix + "-late", 1, "90"))))))
                    .andExpect(jsonPath("$.data.results[0].outcome").value("DISCARDED_OUT_OF_ORDER"))
                    .andExpect(jsonPath("$.data.results[0].observation").isEmpty());
        }
    }

    @Test
    void legacySimulationCannotMutateSharedStateEvenWithAnOwnedRule() throws Exception {
        String token = matchingToken(testSuffix());
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM quote_events", Long.class);
        for (String endpoint : List.of("/api/market/quotes", "/api/market/replay", "/api/market/simulate")) {
            mvc.perform(post(endpoint).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quote_events", Long.class)).isEqualTo(before);
    }

    @Test
    void editsPauseDeleteAndStockMovesKeepCandidatesConsistentWithDatabase() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long originalStock = insertStock("O" + suffix);
        long newStock = insertStock("N" + suffix);
        long movedRule = createMatchingRule(token, originalStock, "ABOVE", "100", "ONCE", null);
        long deletedRule = createMatchingRule(token, originalStock, "ABOVE", "100", "ONCE", null);
        replayQuote(token, originalStock, suffix, 1, "90");
        mvc.perform(post("/api/alert-rules/{id}/pause", movedRule)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0))))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/alert-rules/{id}", deletedRule)
                        .header("Authorization", "Bearer " + token)
                        .param("version", "0"))
                .andExpect(status().isOk());

        // A stale Redis candidate cannot override the authoritative SQL status.
        redis.opsForZSet().add(ruleIndexKey(originalStock), Long.toString(deletedRule), 1_000_000d);
        replayQuote(token, originalStock, suffix, 2, "105");
        assertThat(triggerCount(movedRule)).isZero();
        assertThat(triggerCount(deletedRule)).isZero();

        mvc.perform(put("/api/alert-rules/{id}", movedRule)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "version", 1,
                                "rule", Map.of(
                                        "stockId", newStock,
                                        "name", "Moved threshold",
                                        "direction", "ABOVE",
                                        "thresholdPrice", 110,
                                        "triggerMode", "ONCE")))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/alert-rules/{id}/resume", movedRule)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 2))))
                .andExpect(status().isOk());
        replayQuote(token, originalStock, suffix, 3, "120");
        assertThat(triggerCount(movedRule)).isZero();
        replayQuote(token, newStock, suffix, 1, "100");
        replayQuote(token, newStock, suffix, 2, "110");
        assertThat(triggerCount(movedRule)).isEqualTo(1);
        mvc.perform(get("/api/alert-rules/{id}/triggers", deletedRule)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void missingOrWrongRevisionIndexesAreRebuiltBeforeMatching() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long stockId = insertStock("I" + suffix);
        long ruleId = createMatchingRule(token, stockId, "ABOVE", "100", "REPEAT", "95");
        replayQuote(token, stockId, suffix, 1, "94");
        redis.delete(ruleIndexKey(stockId));
        replayQuote(token, stockId, suffix, 2, "100");
        assertThat(triggerCount(ruleId)).isEqualTo(1);

        redis.delete(ruleIndexKey(stockId));
        redis.opsForZSet().add(ruleIndexKey(stockId), "@stale-revision", -1d);
        assertThat(replayQuote(token, stockId, suffix, 3, "95").path("matching").path("rearmed").asInt())
                .isEqualTo(1);
        replayQuote(token, stockId, suffix, 4, "100");
        assertThat(triggerCount(ruleId)).isEqualTo(2);
        String tokenInDatabase = jdbc.queryForObject(
                "SELECT rule_index_token FROM stocks WHERE id = ?", String.class, stockId);
        assertThat(redis.opsForZSet().rangeByScore(ruleIndexKey(stockId), -1, -1))
                .containsExactly("@" + tokenInDatabase);
    }

    @Test
    void redisMatchingFailureRollsBackQuoteWatermarkAndAllowsSafeRetry() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long stockId = insertStock("F" + suffix);
        long ruleId = createMatchingRule(token, stockId, "ABOVE", "100", "ONCE", null);
        replayQuote(token, stockId, suffix, 1, "95");
        String eventKey = "matching:" + suffix + ":" + stockId + ":2";
        Map<String, Object> event = matchingEvent(stockId, suffix, 2, "100");
        redis.delete(ruleIndexKey(stockId));
        redis.opsForValue().set(ruleIndexKey(stockId), "wrong-type");
        try {
            mvc.perform(post("/test/real-quotes/replay")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("events", List.of(event)))))
                    .andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quote_events WHERE event_key = ?", Integer.class, eventKey))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT latest_price FROM stocks WHERE id = ?", BigDecimal.class, stockId))
                    .isEqualByComparingTo("95");
            assertThat(triggerCount(ruleId)).isZero();
            assertThat(ruleStatus(ruleId)).isEqualTo("ACTIVE");
        } finally {
            redis.delete(ruleIndexKey(stockId));
        }
        assertThat(replayQuote(token, stockId, suffix, 2, "100").path("matching").path("triggered").asInt())
                .isEqualTo(1);
        assertThat(triggerCount(ruleId)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateQuotesCommitOneTriggerAndOneOutboxEvent() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long stockId = insertStock("Q" + suffix);
        long ruleId = createMatchingRule(token, stockId, "ABOVE", "100", "ONCE", null);
        replayQuote(token, stockId, suffix, 1, "95");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return replayQuote(token, stockId, suffix, 2, "100").path("outcome").asText();
            });
            var second = executor.submit(() -> {
                start.await();
                return replayQuote(token, stockId, suffix, 2, "100").path("outcome").asText();
            });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("PROCESSED", "DUPLICATE");
        }
        assertThat(triggerCount(ruleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o
                JOIN alert_triggers t ON t.id = o.aggregate_id
                WHERE t.rule_id = ?
                """, Integer.class, ruleId)).isEqualTo(1);
    }

    @Test
    void rolledBackTriggerCannotLeaveAnApparentlyCurrentRedisIndexOnRetry() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long stockId = insertStock("Z" + suffix);
        long ruleId = createMatchingRule(token, stockId, "ABOVE", "100", "ONCE", null);
        replayQuote(token, stockId, suffix, 1, "95");
        String committedToken = jdbc.queryForObject(
                "SELECT rule_index_token FROM stocks WHERE id = ?", String.class, stockId);
        String eventKey = "matching:" + suffix + ":" + stockId + ":2";
        var event = new QuoteInputRequest(
                eventKey, stockId, new BigDecimal("100"),
                LocalDateTime.of(2024, 6, 1, 10, 0, 2), "TEST", 2L);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        long[] rolledBackTriggerId = {0};

        transaction.executeWithoutResult(state -> {
            var result = realFixture.replay(null, new ReplayRequest(List.of(event)));
            assertThat(result.results().getFirst().matching().triggered()).isEqualTo(1);
            assertThat(ruleStatus(ruleId)).isEqualTo("COMPLETED");
            rolledBackTriggerId[0] = jdbc.queryForObject(
                    "SELECT id FROM alert_triggers WHERE rule_id = ?", Long.class, ruleId);
            state.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
                "SELECT rule_index_token FROM stocks WHERE id = ?", String.class, stockId))
                .isEqualTo(committedToken);
        var rolledBackCacheMarker = redis.opsForZSet().rangeByScore(ruleIndexKey(stockId), -1, -1);
        assertThat(rolledBackCacheMarker).hasSize(1).doesNotContain("@" + committedToken);
        assertThat(jdbc.queryForObject(
                "SELECT latest_price FROM stocks WHERE id = ?", BigDecimal.class, stockId))
                .isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quote_events WHERE event_key = ?", Integer.class, eventKey))
                .isZero();
        assertThat(ruleStatus(ruleId)).isEqualTo("ACTIVE");
        assertThat(triggerCount(ruleId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, rolledBackTriggerId[0])).isZero();

        assertThat(replayQuote(token, stockId, suffix, 2, "100").path("matching").path("triggered").asInt())
                .isEqualTo(1);
        assertThat(triggerCount(ruleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_events o
                JOIN alert_triggers t ON t.id = o.aggregate_id
                WHERE t.rule_id = ? AND o.status = 'PENDING'
                """, Integer.class, ruleId)).isEqualTo(1);
        assertThat(redis.opsForZSet().rangeByScore(ruleIndexKey(stockId), -1, -1))
                .doesNotContainAnyElementsOf(rolledBackCacheMarker);
    }

    @Test
    void outboxClaimsRetryAndLeaseRecoveryPreserveAtLeastOnceDelivery() throws Exception {
        jdbc.update("UPDATE outbox_events SET status = 'SENT', published_at = NOW(6) WHERE status != 'SENT'");
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long stockId = insertStock("X" + suffix);
        long ruleId = createMatchingRule(token, stockId, "ABOVE", "100", "ONCE", null);
        replayQuote(token, stockId, suffix, 1, "95");
        replayQuote(token, stockId, suffix, 2, "100");
        long outboxId = jdbc.queryForObject("""
                SELECT o.id FROM outbox_events o
                JOIN alert_triggers t ON t.id = o.aggregate_id
                WHERE t.rule_id = ?
                """, Long.class, ruleId);

        List<OutboxEvent> firstClaim = outboxDispatchService.claimBatch();
        OutboxEvent event = firstClaim.stream()
                .filter(candidate -> candidate.getId() == outboxId)
                .findFirst().orElseThrow();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, outboxId))
                .isEqualTo("SENDING");

        assertThat(outboxDispatchService.markFailed(event, new IllegalStateException("broker unavailable")))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, outboxId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT last_error FROM outbox_events WHERE id = ?", String.class, outboxId))
                .contains("broker unavailable");

        jdbc.update("UPDATE outbox_events SET next_retry_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?",
                outboxId);
        OutboxEvent secondClaim = outboxDispatchService.claimBatch().stream()
                .filter(candidate -> candidate.getId() == outboxId)
                .findFirst().orElseThrow();
        assertThat(secondClaim.getAttempts()).isEqualTo(2);

        // A crash after claiming leaves SENDING behind. Once the lease expires another run can recover it.
        jdbc.update("""
                UPDATE outbox_events
                SET locked_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE), locked_by = 'dead-worker'
                WHERE id = ?
                """, outboxId);
        OutboxEvent recovered = outboxDispatchService.claimBatch().stream()
                .filter(candidate -> candidate.getId() == outboxId)
                .findFirst().orElseThrow();
        assertThat(recovered.getAttempts()).isEqualTo(3);
        assertThat(outboxDispatchService.markPublished(outboxId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, outboxId))
                .isEqualTo("SENT");

        jdbc.update("""
                INSERT INTO outbox_events
                    (aggregate_type, aggregate_id, event_type, payload, status, attempts, locked_at, locked_by)
                VALUES ('ALERT_TRIGGER', ?, 'ALERT_TRIGGERED', '{}', 'SENDING', 8,
                        DATE_SUB(NOW(6), INTERVAL 1 MINUTE), 'crashed-final-worker')
                """, jdbc.queryForObject(
                "SELECT id FROM alert_triggers WHERE rule_id = ?", Long.class, ruleId));
        long exhaustedId = jdbc.queryForObject(
                "SELECT MAX(id) FROM outbox_events WHERE aggregate_id = (SELECT id FROM alert_triggers WHERE rule_id = ?)",
                Long.class, ruleId);
        assertThat(outboxDispatchService.claimBatch()).noneMatch(candidate -> candidate.getId() == exhaustedId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, exhaustedId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT locked_by FROM outbox_events WHERE id = ?", String.class, exhaustedId))
                .isNull();
    }

    @Test
    void duplicateAlertMessagesCreateOneOwnedNotificationThatCanBeMarkedRead() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        String outsider = matchingToken(testSuffix());
        long stockId = insertStock("D" + suffix);
        long ruleId = createMatchingRule(token, stockId, "BELOW", "90", "ONCE", null);
        replayQuote(token, stockId, suffix, 1, "100");
        replayQuote(token, stockId, suffix, 2, "90");
        long triggerId = jdbc.queryForObject(
                "SELECT id FROM alert_triggers WHERE rule_id = ?", Long.class, ruleId);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?", String.class, triggerId);

        // Delivery is delayed. Even if the mutable rule is moved meanwhile, the event uses its trigger snapshot.
        long laterStockId = insertStock("E" + suffix);
        jdbc.update("UPDATE alert_rules SET stock_id = ? WHERE id = ?", laterStockId, ruleId);

        notificationService.consume(payload);
        notificationService.consume(payload);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE trigger_id = ?", Integer.class, triggerId))
                .isEqualTo(1);
        long notificationId = jdbc.queryForObject(
                "SELECT id FROM notifications WHERE trigger_id = ?", Long.class, triggerId);

        mvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(notificationId))
                .andExpect(jsonPath("$.data[0].triggerId").value(triggerId))
                .andExpect(jsonPath("$.data[0].ruleId").value(ruleId))
                .andExpect(jsonPath("$.data[0].stockId").value(stockId))
                .andExpect(jsonPath("$.data[0].status").value("UNREAD"))
                .andExpect(jsonPath("$.data[0].title").value("D" + suffix + " 已下破 90.00"))
                .andExpect(jsonPath("$.data[0].content").value(org.hamcrest.Matchers.containsString("价格 90.00，")));
        mvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READ"))
                .andExpect(jsonPath("$.data.readAt").isNotEmpty());
        // Marking read is deliberately idempotent and keeps the original read time.
        mvc.perform(post("/api/notifications/{id}/read", notificationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READ"));
        mvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "unknown"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void notificationFeedAndBulkReadRequireAuthenticationAndHandleAnEmptyAccount() throws Exception {
        mvc.perform(get("/api/notifications/feed")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        String token = matchingToken(testSuffix());
        mvc.perform(get("/api/notifications/feed").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andExpect(jsonPath("$.data.latest.length()").value(0));
        mvc.perform(post("/api/notifications/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedRead").value(0));
    }

    @Test
    void notificationCountsIncludeOlderRowsAndBulkReadIsIsolatedAndIdempotent() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        String outsider = matchingToken(testSuffix());
        long firstId = seedNotificationHistory(token, suffix, 105);
        seedNotificationHistory(outsider, testSuffix(), 1);
        mvc.perform(get("/api/notifications/feed").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(105))
                .andExpect(jsonPath("$.data.unreadCount").value(105))
                .andExpect(jsonPath("$.data.latest.length()").value(20));
        mvc.perform(get("/api/notifications").param("limit", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(100));

        // The oldest unread row is outside both the feed and the visible list.
        mvc.perform(post("/api/notifications/{id}/read", firstId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        LocalDateTime firstReadAt = jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?", LocalDateTime.class, firstId);
        mvc.perform(get("/api/notifications/feed").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalCount").value(105))
                .andExpect(jsonPath("$.data.unreadCount").value(104));
        mvc.perform(post("/api/notifications/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedRead").value(104));
        mvc.perform(post("/api/notifications/read-all").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markedRead").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?", LocalDateTime.class, firstId))
                .isEqualTo(firstReadAt);
        mvc.perform(get("/api/notifications/feed").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalCount").value(105))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(get("/api/notifications/feed").header("Authorization", "Bearer " + outsider))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.unreadCount").value(1));

        // Marking all read affects the current rows, not notifications created afterward.
        seedNotificationHistory(token, testSuffix(), 1);
        mvc.perform(get("/api/notifications/feed").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.totalCount").value(106))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.latest[0].status").value("UNREAD"));
    }

    @Test
    void priceLimitsAndMicrosecondPrecisionAreValidatedBeforeMatching() throws Exception {
        String suffix = testSuffix();
        String token = matchingToken(suffix);
        long stockId = insertStock("P" + suffix);
        mvc.perform(post("/test/real-quotes/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("events", List.of(
                                matchingEvent(stockId, suffix, 1, "100000000000"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM quote_events WHERE stock_id = ?", Integer.class, stockId))
                .isZero();

        var preciseEvent = new java.util.LinkedHashMap<>(matchingEvent(stockId, suffix, 2, "100"));
        preciseEvent.put("eventTime", LocalDateTime.of(2024, 6, 1, 10, 0, 2, 987_654_321));
        String body = json(Map.of("events", List.of(preciseEvent)));
        mvc.perform(post("/test/real-quotes/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].outcome").value("PROCESSED"));
        mvc.perform(post("/test/real-quotes/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].outcome").value("DUPLICATE"));
        assertThat(jdbc.queryForObject(
                "SELECT event_time FROM quote_events WHERE stock_id = ?", LocalDateTime.class, stockId))
                .isEqualTo(LocalDateTime.of(2024, 6, 1, 10, 0, 2, 987_654_000));
    }

    private String testSuffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private long seedNotificationHistory(String token, String suffix, int count) throws Exception {
        long stockId = insertStock("NOTICE" + suffix);
        long ruleId = createMatchingRule(token, stockId, "BELOW", "90", "ONCE", null);
        replayQuote(token, stockId, suffix, 1, "100");
        replayQuote(token, stockId, suffix, 2, "90");
        long triggerId = jdbc.queryForObject(
                "SELECT id FROM alert_triggers WHERE rule_id = ?", Long.class, ruleId);
        notificationService.consume(jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?", String.class, triggerId));
        long firstId = jdbc.queryForObject(
                "SELECT id FROM notifications WHERE trigger_id = ?", Long.class, triggerId);
        // Valid persisted snapshots make it cheap to populate history without replaying 100 rules.
        for (int index = 1; index < count; index++) {
            String triggerKey = "notification-history:" + suffix + ":" + index;
            jdbc.update("""
                    INSERT INTO alert_triggers
                        (trigger_key, rule_id, quote_event_id, trigger_price, event_time, rule_snapshot)
                    SELECT ?, rule_id, quote_event_id, trigger_price, event_time, rule_snapshot
                    FROM alert_triggers WHERE id = ?
                    """, triggerKey, triggerId);
            jdbc.update("""
                    INSERT INTO notifications (user_id, trigger_id, title, content)
                    SELECT n.user_id, t.id, n.title, n.content
                    FROM notifications n JOIN alert_triggers t ON t.trigger_key = ?
                    WHERE n.id = ?
                    """, triggerKey, firstId);
        }
        return firstId;
    }

    private String matchingToken(String suffix) throws Exception {
        return register("matching-" + suffix.toLowerCase() + "@example.com", "Matching Test", "matching-password-123");
    }

    private long createMatchingRule(
            String token, long stockId, String direction, String threshold, String mode, String rearm)
            throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("stockId", stockId);
        body.put("name", direction + " " + threshold);
        body.put("direction", direction);
        body.put("thresholdPrice", new BigDecimal(threshold));
        body.put("triggerMode", mode);
        if (rearm != null) {
            body.put("rearmPrice", new BigDecimal(rearm));
        }
        String response = mvc.perform(post("/api/alert-rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private JsonNode replayQuote(String token, long stockId, String run, int step, String price) throws Exception {
        String response = mvc.perform(post("/test/real-quotes/replay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("events", List.of(matchingEvent(stockId, run, step, price))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("results").get(0);
    }

    private Map<String, Object> matchingEvent(long stockId, String run, int step, String price) {
        return Map.of(
                "eventKey", "matching:" + run + ":" + stockId + ":" + step,
                "stockId", stockId,
                "price", new BigDecimal(price),
                "eventTime", LocalDateTime.of(2024, 6, 1, 10, 0).plusSeconds(step),
                "source", "TEST",
                "sequenceNo", step);
    }

    private int triggerCount(long ruleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id = ?", Integer.class, ruleId);
    }

    private String ruleStatus(long ruleId) {
        return jdbc.queryForObject("SELECT status FROM alert_rules WHERE id = ?", String.class, ruleId);
    }

    private long ruleVersion(long ruleId) {
        return jdbc.queryForObject("SELECT version FROM alert_rules WHERE id = ?", Long.class, ruleId);
    }

    private String ruleIndexKey(long stockId) {
        return com.ezra.pricepulse.matching.RuleIndex.key(stockId);
    }

    private long insertStock(String symbol) {
        jdbc.update("""
                INSERT INTO stocks (symbol, name, market, currency, status)
                VALUES (?, 'Phase 3 Test Stock', 'SIM', 'USD', 'ACTIVE')
                """, symbol);
        return jdbc.queryForObject(
                "SELECT id FROM stocks WHERE market = 'SIM' AND symbol = ?", Long.class, symbol);
    }

    private String register(String email, String displayName, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "email", email,
                                "displayName", displayName,
                                "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("accessToken").asText();
    }

    private String expiredToken() {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("price-pulse")
                .subject("1")
                .issuedAt(now.minusSeconds(7200))
                .expiresAt(now.minusSeconds(3600))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
