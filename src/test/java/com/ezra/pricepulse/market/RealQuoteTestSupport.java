package com.ezra.pricepulse.market;

import com.ezra.pricepulse.alert.AlertEvaluationService;
import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.user.CurrentUserId;
import jakarta.validation.Valid;
import java.time.*;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.*;

/** Not packaged in the application. Historical fixtures cannot become public ingestion routes. */
@TestConfiguration(proxyBeanMethods = false)
@Import(RealQuoteTestSupport.Controller.class)
public class RealQuoteTestSupport {
    @Bean @Primary FixtureClock fixtureClock() { return new FixtureClock(); }
    @Bean Fixture fixture(QuoteIngestionService ingestion, AlertEvaluationService rules, FixtureClock clock) {
        return new Fixture(ingestion, rules, clock);
    }

    public static class FixtureClock extends Clock {
        private final ThreadLocal<Instant> time = new ThreadLocal<>();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return time.get() == null ? Instant.now() : time.get(); }
        public <T> T at(LocalDateTime instant, Supplier<T> work) {
            Instant previous = time.get();
            time.set(instant.toInstant(ZoneOffset.UTC));
            try { return work.get(); }
            finally { if (previous == null) time.remove(); else time.set(previous); }
        }
    }

    public record Fixture(QuoteIngestionService ingestion, AlertEvaluationService rules, FixtureClock clock) {
        public BatchQuoteResponse replay(Long userId, ReplayRequest request) {
            RuleTraceSelection trace = null;
            if (request.ruleId() != null) {
                long stock = request.events().getFirst().stockId();
                rules.requireObservedRule(userId, request.ruleId(), stock);
                if (request.events().stream().anyMatch(e -> e.stockId() != stock)) {
                    throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Trace must use one stock");
                }
                trace = new RuleTraceSelection(userId, request.ruleId());
            }
            RuleTraceSelection selection = trace;
            return BatchQuoteResponse.from(request.events().stream().map(e -> clock.at(e.eventTime(),
                    () -> ingestion.ingest(QuoteCandidate.live(e), QuoteIngestionPolicy.LIVE, selection))).toList());
        }
    }

    @TestComponent
    @RestController
    @org.springframework.context.annotation.Profile("integration | performance")
    @RequestMapping("/test/real-quotes")
    public static class Controller {
        private final Fixture fixture;
        public Controller(Fixture fixture) { this.fixture = fixture; }
        @PostMapping("/live")
        ApiResponse<QuoteResult> live(@Valid @RequestBody QuoteInputRequest request) {
            return ApiResponse.success(fixture.ingestion().ingest(QuoteCandidate.live(request), QuoteIngestionPolicy.LIVE));
        }
        @PostMapping("/replay")
        ApiResponse<BatchQuoteResponse> replay(@CurrentUserId Long userId, @Valid @RequestBody ReplayRequest request) {
            return ApiResponse.success(fixture.replay(userId, request));
        }
    }
}
