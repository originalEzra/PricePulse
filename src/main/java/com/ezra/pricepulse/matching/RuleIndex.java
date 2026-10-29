package com.ezra.pricepulse.matching;

import com.ezra.pricepulse.alert.AlertRule;
import com.ezra.pricepulse.alert.AlertRuleMapper;
import com.ezra.pricepulse.alert.AlertRuleStatus;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Real-market adapter; callers hold the corresponding MySQL stock row lock. */
@Component
@Profile("!standalone & !mq-it")
public class RuleIndex {
    private final ThresholdIndex cache;
    private final AlertRuleMapper rules;
    private final StockMapper stocks;
    private final Timer lookupTimer;
    private final Timer rebuildTimer;
    private final DistributionSummary candidateCounts;

    public RuleIndex(ThresholdIndex cache, AlertRuleMapper rules, StockMapper stocks, MeterRegistry metrics) {
        this.cache = cache; this.rules = rules; this.stocks = stocks;
        lookupTimer = Timer.builder("pricepulse.rules.lookup")
                .description("Real candidate lookup including rebuild; excludes stock lock")
                .publishPercentiles(0.95, 0.99).register(metrics);
        rebuildTimer = Timer.builder("pricepulse.rules.rebuild").register(metrics);
        candidateCounts = DistributionSummary.builder("pricepulse.rules.candidates").register(metrics);
    }

    public List<Long> candidates(Stock stock, BigDecimal previous, BigDecimal current) {
        Timer.Sample sample = Timer.start();
        try {
            List<Long> ids = cache.candidates(key(stock.id()), stock.ruleIndexToken(), previous, current,
                    () -> rules.listIndexable(stock.id()).stream().map(RuleIndexEntry::from).toList(), rebuildTimer);
            candidateCounts.record(ids.size());
            return ids;
        } finally { sample.stop(lookupTimer); }
    }

    public void changed(Stock stock, List<AlertRule> changedRules) {
        String next = UUID.randomUUID().toString();
        if (stocks.updateRuleIndexToken(stock.id(), next) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Stock index revision changed");
        }
        List<RuleIndexEntry> entries = changedRules.stream().map(rule ->
                rule.getStockId() == stock.id() ? RuleIndexEntry.from(rule)
                        : new RuleIndexEntry(rule.getId(), null, AlertRuleStatus.DELETED)).toList();
        cache.changed(key(stock.id()), stock.ruleIndexToken(), next, entries);
    }

    public static String key(long stockId) { return "pricepulse:v2:real:rules:{stock:" + stockId + "}"; }
}
