package com.ezra.pricepulse.matching;

import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Reusable cache mechanics; each scope supplies only its own persisted state. */
@Component
@Profile("!standalone & !mq-it")
public class ThresholdIndex {
    private static final DefaultRedisScript<Long> REBUILD = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            for i = 2, #ARGV, 2 do redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1]) end
            redis.call('ZADD', KEYS[1], -1, '@' .. ARGV[1])
            redis.call('EXPIRE', KEYS[1], 86400)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CHANGE = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], '@' .. ARGV[1]) ~= '-1' then return 0 end
            redis.call('ZREM', KEYS[1], '@' .. ARGV[1])
            for i = 3, #ARGV, 2 do
                redis.call('ZREM', KEYS[1], ARGV[i])
                if ARGV[i + 1] ~= 'REMOVE' then redis.call('ZADD', KEYS[1], ARGV[i + 1], ARGV[i]) end
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
    public ThresholdIndex(StringRedisTemplate redis) { this.redis = redis; }

    public List<Long> candidates(String key, String token, BigDecimal previous, BigDecimal current,
            Supplier<List<RuleIndexEntry>> load) {
        return candidates(key, token, previous, current, load, null);
    }

    public List<Long> candidates(String key, String token, BigDecimal previous, BigDecimal current,
            Supplier<List<RuleIndexEntry>> load, Timer rebuildTimer) {
        List<String> args = new ArrayList<>(List.of(token));
        if (previous != null && current.compareTo(previous) != 0) {
            long oldTick = PriceTicks.ticks(previous), newTick = PriceTicks.ticks(current);
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
                List<?> result = redis.execute(QUERY, List.of(key), args.toArray());
                if (result != null && !result.isEmpty() && "HIT".equals(result.getFirst())) {
                    return result.stream().skip(1).map(Object::toString).map(Long::parseLong).distinct().toList();
                }
                Timer.Sample sample = rebuildTimer == null ? null : Timer.start();
                try {
                    List<String> rebuild = new ArrayList<>(List.of(token));
                    for (RuleIndexEntry entry : load.get()) {
                        Long score = entry.score();
                        if (score != null) { rebuild.add(score.toString()); rebuild.add(Long.toString(entry.id())); }
                    }
                    redis.execute(REBUILD, List.of(key), rebuild.toArray());
                } finally {
                    if (sample != null) sample.stop(rebuildTimer);
                }
            }
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Rule index is unavailable; retry with the same request key");
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Rule index changed during lookup; retry");
    }

    public void changed(String key, String before, String next, List<RuleIndexEntry> entries) {
        List<String> args = new ArrayList<>(List.of(before, next));
        for (RuleIndexEntry entry : entries) {
            args.add(Long.toString(entry.id()));
            Long score = entry.score();
            args.add(score == null ? "REMOVE" : score.toString());
        }
        try { redis.execute(CHANGE, List.of(key), args.toArray()); }
        catch (DataAccessException ignored) {
            // The committed DB token forces a rebuild on the next lookup.
        }
    }

    private void range(List<String> args, long band, long low, boolean lowInclusive, long high, boolean highInclusive) {
        args.add((lowInclusive ? "" : "(") + (band * PriceTicks.BAND + low));
        args.add((highInclusive ? "" : "(") + (band * PriceTicks.BAND + high));
    }

    public void retire(String key) {
        try { redis.delete(key); }
        catch (DataAccessException ignored) { /* Obsolete UUID keys also have a TTL. */ }
    }
}
