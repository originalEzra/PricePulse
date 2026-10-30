package com.ezra.pricepulse.market;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MarketFeedHealth {
    private static final Logger log=LoggerFactory.getLogger(MarketFeedHealth.class);
    private volatile String connection = "DISABLED", lastError;
    private volatile Set<String> confirmedSymbols=Set.of();
    private final ConcurrentMap<String,Instant> observedAt = new ConcurrentHashMap<>();
    private final AtomicLong observed = new AtomicLong(), rejected = new AtomicLong(), overflows = new AtomicLong();
    private long connectionAttempts, subscriptions, receivedFrames, sentPings, receivedPongs, heartbeatTimeouts, clientTimingGaps;
    private Instant lastConnectAttemptAt, lastSubscribedAt, lastFrameAt, lastPingAt, lastPongAt, lastFailureAt, lastCloseAt;
    private String lastFailureCode;
    private Integer lastCloseCode;
    private Instant lastCloseRequestedAt,lastCloseSentAt;
    private final ArrayDeque<MarketDataModels.StreamFailure> recentFailures=new ArrayDeque<>();
    public void connection(String state) { connection = state;if(!state.equals("SUBSCRIBED"))confirmedSymbols=Set.of(); }
    public boolean confirms(String symbol){return connection.equals("SUBSCRIBED")&&confirmedSymbols.contains(symbol);}
    public void subscriptionSymbols(Collection<String> symbols){confirmedSymbols=Set.copyOf(symbols);}
    public void error(String safeCode) { lastError = safeCode; }
    public void observed(String symbol, Instant eventTime) {
        observed.incrementAndGet(); observedAt.merge(symbol,eventTime,(a,b)->a.isAfter(b)?a:b);
    }
    public void reject() { rejected.incrementAndGet(); }
    public void overflow() { overflows.incrementAndGet(); error("QUEUE_OVERFLOW"); }
    public String connection() { return connection; }
    public synchronized void streamAttempt(Instant at) { connectionAttempts++; lastConnectAttemptAt=at; }
    public synchronized void streamSubscribed(Instant at,Collection<String> symbols) { subscriptions++; lastSubscribedAt=at;confirmedSymbols=Set.copyOf(symbols); }
    public synchronized void streamFrame(Instant at) { receivedFrames++; lastFrameAt=at; }
    public synchronized void streamPing(Instant at) { sentPings++; lastPingAt=at; }
    public synchronized void streamPong(Instant at) { receivedPongs++; lastPongAt=at; }
    public synchronized void streamFailure(String code,Instant at) {
        streamFailure(code,at,null,null,null);
    }
    public synchronized void streamFailure(String code,Instant at,Long schedulerDelayMillis,Long receiveSilenceMillis,Long clockDriftMillis) {
        lastFailureCode=code; lastFailureAt=at; error(code);
        if (code.equals("HEARTBEAT_TIMEOUT")) heartbeatTimeouts++;
        if (code.equals("CLIENT_TIMING_GAP")) clientTimingGaps++;
        recentFailures.addLast(new MarketDataModels.StreamFailure(at,code,connectionAttempts,lastFrameAt,lastPingAt,lastPongAt,
                schedulerDelayMillis,receiveSilenceMillis,clockDriftMillis));
        if (recentFailures.size()>20) recentFailures.removeFirst();
        // Only local codes, counters and timestamps: never provider messages, frames or credentials.
        log.warn("Market stream failure code={} at={} attempt={} schedulerDelayMs={} silenceMs={} clockDriftMs={} lastPong={} closeRequested={} closeSent={}",
                code,at,connectionAttempts,schedulerDelayMillis,receiveSilenceMillis,clockDriftMillis,lastPongAt,lastCloseRequestedAt,lastCloseSentAt);
    }
    public synchronized void streamCloseRequested(Instant at) { lastCloseRequestedAt=at;lastCloseSentAt=null; }
    public synchronized void streamCloseSent(Instant at) { lastCloseSentAt=at; }
    public synchronized void streamClosed(int code,Instant at) { lastCloseCode=code; lastCloseAt=at; }
    public synchronized MarketDataModels.StreamDiagnostics streamSnapshot() {
        return new MarketDataModels.StreamDiagnostics(connectionAttempts,subscriptions,receivedFrames,sentPings,
                receivedPongs,heartbeatTimeouts,lastConnectAttemptAt,lastSubscribedAt,lastFrameAt,lastPingAt,
                lastPongAt,lastFailureAt,lastFailureCode,lastCloseAt,lastCloseCode,clientTimingGaps,
                lastCloseRequestedAt,lastCloseSentAt,List.copyOf(recentFailures));
    }
    public MarketDataModels.Health snapshot(String symbol, boolean open, Instant now,
            MarketDataModels.StreamState state, boolean writes) {
        Instant observedTime = observedAt.get(symbol);
        Instant accepted = state == null ? null : MarketTime.instant(state.eventTimeNs());
        String freshness = !open ? "MARKET_CLOSED" : observedTime == null ? "NO_DATA"
                : observedTime.isBefore(now.minusSeconds(120)) ? "DELAYED" : "RECENT_IEX_TRADE";
        String scopedConnection=connection.equals("SUBSCRIBED")&&!confirms(symbol)?"NOT_SUBSCRIBED":connection;
        return new MarketDataModels.Health(scopedConnection,open?"OPEN":"CLOSED",freshness,writes,
                observedTime,accepted,observed.get(),rejected.get(),overflows.get(),lastError,streamSnapshot());
    }
}
