package com.ezra.pricepulse.matching;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertRule;
import com.ezra.pricepulse.alert.AlertRuleMapper;
import com.ezra.pricepulse.alert.AlertRuleStatus;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** All callers hold the corresponding MySQL stock row lock. */
@Component
@Profile("!standalone & !mq-it")
public class RuleIndex {
    private static final Logger log = LoggerFactory.getLogger(RuleIndex.class);
    private static final DefaultRedisScript<Long> REBUILD = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            for i = 2, #ARGV, 2 do
                redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            redis.call('ZADD', KEYS[1], -1, '@' .. ARGV[1])
            redis.call('EXPIRE', KEYS[1], 86400)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CHANGE = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], '@' .. ARGV[1]) ~= '-1' then return 0 end
            redis.call('ZREM', KEYS[1], '@' .. ARGV[1])
            for i = 3, #ARGV, 2 do
                redis.call('ZREM', KEYS[1], ARGV[i])
                if ARGV[i + 1] ~= 'REMOVE' then
                    redis.call('ZADD', KEYS[1], ARGV[i + 1], ARGV[i])
                end
            end
            redis.call('ZADD', KEYS[1], -1, '@' .. ARGV[2])
            redis.call('EXPIRE', KEYS[1], 86400)
            return 1
            """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> QUERY = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], '@' .. ARGV[1]) ~= '-1' then return {'MISS'} end
            local result = {'HIT'}
            for i = 2, #ARGV, 2 do
                local ids = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[i], ARGV[i + 1])
                for _, id in ipairs(ids) do table.insert(result, id) end
            end
            return result
            """, List.class);

    private final StringRedisTemplate redis;
    private final AlertRuleMapper rules;
    private final StockMapper stocks;
    private final Timer lookupTimer;
    private final Timer rebuildTimer;
    private final DistributionSummary candidateCounts;

    public RuleIndex(StringRedisTemplate redis, AlertRuleMapper rules, StockMapper stocks, MeterRegistry metrics) {
        this.redis = redis;
        this.rules = rules;
        this.stocks = stocks;
        this.lookupTimer = Timer.builder("pricepulse.rules.lookup")
                .description("Candidate lookup, including rebuilds when needed; excludes stock lock acquisition")
                .publishPercentiles(0.95, 0.99).register(metrics);
        this.rebuildTimer = Timer.builder("pricepulse.rules.rebuild")
                .description("Full per-stock rule index rebuild").register(metrics);
        this.candidateCounts = DistributionSummary.builder("pricepulse.rules.candidates")
                .description("Candidates per successful lookup, not committed business triggers")
                .register(metrics);
    }

    public List<Long> candidates(Stock stock, BigDecimal previous, BigDecimal current) {
        Timer.Sample sample = Timer.start();
        try {
            List<Long> candidates = queryCandidates(stock, previous, current);
            candidateCounts.record(candidates.size());
            return candidates;
        } finally {
            sample.stop(lookupTimer);
        }
    }

    private List<Long> queryCandidates(Stock stock, BigDecimal previous, BigDecimal current) {
        List<String> args = new ArrayList<>();
        args.add(stock.ruleIndexToken());
        if (previous != null && current.compareTo(previous) != 0) {
            long oldTick = PriceTicks.ticks(previous);
            long newTick = PriceTicks.ticks(current);
            if (newTick > oldTick) {
                range(args, 0, oldTick, false, newTick, true);
                range(args, 3, oldTick, false, newTick, true);
            } else {
                range(args, 1, newTick, true, oldTick, false);
                range(args, 2, newTick, true, oldTick, false);
            }
        }
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                List<?> result = redis.execute(QUERY, List.of(key(stock.id())), args.toArray());
                if (result != null && !result.isEmpty() && "HIT".equals(result.getFirst())) {
                    return result.stream().skip(1).map(Object::toString).map(Long::parseLong).distinct().toList();
                }
                rebuild(stock);
            }
        } catch (DataAccessException exception) {
            log.warn("Rule index unavailable for stock {}", stock.id());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Rule index is unavailable; this quote was not accepted, retry with the same eventKey");
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Rule index changed during lookup; retry");
    }

    public void changed(Stock stock, List<AlertRule> changedRules) {
        String nextToken = UUID.randomUUID().toString();
        if (stocks.updateRuleIndexToken(stock.id(), nextToken) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Stock index revision changed");
        }
        List<String> args = new ArrayList<>(List.of(stock.ruleIndexToken(), nextToken));
        for (AlertRule rule : changedRules) {
            args.add(rule.getId().toString());
            Long score = rule.getStockId() == stock.id() ? score(rule) : null;
            args.add(score == null ? "REMOVE" : score.toString());
        }
        try {
            redis.execute(CHANGE, List.of(key(stock.id())), args.toArray());
        } catch (DataAccessException exception) {
            // Committed UUID differs from the cache token, forcing a rebuild before any later match.
            log.warn("Deferred rule index synchronization for stock {}", stock.id());
        }
    }

    private void rebuild(Stock stock) {
        Timer.Sample sample = Timer.start();
        try {
            rebuildFromDatabase(stock);
        } finally {
            sample.stop(rebuildTimer);
        }
    }

    private void rebuildFromDatabase(Stock stock) {
        List<String> args = new ArrayList<>();
        args.add(stock.ruleIndexToken());
        for (AlertRule rule : rules.listIndexable(stock.id())) {
            args.add(score(rule).toString());
            args.add(rule.getId().toString());
        }
        redis.execute(REBUILD, List.of(key(stock.id())), args.toArray());
    }

    private Long score(AlertRule rule) {
        if (rule.getStatus() == AlertRuleStatus.ACTIVE) {
            long band = rule.getDirection() == AlertDirection.ABOVE ? 0 : 1;
            return band * PriceTicks.BAND + PriceTicks.ticks(rule.getThresholdPrice());
        }
        if (rule.getStatus() == AlertRuleStatus.COOLDOWN) {
            long band = rule.getDirection() == AlertDirection.ABOVE ? 2 : 3;
            return band * PriceTicks.BAND + PriceTicks.ticks(rule.getRearmPrice());
        }
        return null;
    }

    private void range(List<String> args, long band, long low, boolean lowInclusive,
            long high, boolean highInclusive) {
        args.add((lowInclusive ? "" : "(") + (band * PriceTicks.BAND + low));
        args.add((highInclusive ? "" : "(") + (band * PriceTicks.BAND + high));
    }

    public static String key(long stockId) {
        return "pricepulse:rules:{" + stockId + "}";
    }
}
