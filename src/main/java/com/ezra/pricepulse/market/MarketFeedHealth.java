package com.ezra.pricepulse.market;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;

@Component
public class MarketFeedHealth {
    private volatile String connection = "DISABLED", lastError;
    private final ConcurrentMap<String,Instant> observedAt = new ConcurrentHashMap<>();
    private final AtomicLong observed = new AtomicLong(), rejected = new AtomicLong(), overflows = new AtomicLong();
    public void connection(String state) { connection = state; }
    public void error(String safeCode) { lastError = safeCode; }
    public void observed(String symbol, Instant eventTime) {
        observed.incrementAndGet(); observedAt.merge(symbol,eventTime,(a,b)->a.isAfter(b)?a:b);
    }
    public void reject() { rejected.incrementAndGet(); }
    public void overflow() { overflows.incrementAndGet(); error("QUEUE_OVERFLOW"); }
    public String connection() { return connection; }
    public MarketDataModels.Health snapshot(String symbol, boolean open, Instant now,
            MarketDataModels.StreamState state, boolean writes) {
        Instant observedTime = observedAt.get(symbol);
        Instant accepted = state == null ? null : MarketTime.instant(state.eventTimeNs());
        String freshness = !open ? "MARKET_CLOSED" : observedTime == null ? "NO_DATA"
                : observedTime.isBefore(now.minusSeconds(120)) ? "DELAYED" : "RECENT_IEX_TRADE";
        return new MarketDataModels.Health(connection,open?"OPEN":"CLOSED",freshness,writes,
                observedTime,accepted,observed.get(),rejected.get(),overflows.get(),lastError);
    }
}
