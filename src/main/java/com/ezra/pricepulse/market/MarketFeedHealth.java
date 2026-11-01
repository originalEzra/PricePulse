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
    private final LinkedHashMap<String,MonitorFault> faults=new LinkedHashMap<>();
    private String connectionFault;
    private final Map<Long,String> queueFaults=new HashMap<>();
    public synchronized List<MonitorFault> faults(){return List.copyOf(faults.values());}
    public synchronized void restoreFault(MonitorFault value){
        if(value.recoveredAt()!=null)return;
        faults.putIfAbsent(value.id(),value);
        if(value.stockId()==null)connectionFault=value.id();else queueFaults.put(value.stockId(),value.id());
    }
    public synchronized String connectionFaultId(){return connectionFault;}
    public synchronized boolean queueFaultOpen(long stock){return queueFaults.containsKey(stock);}
    public synchronized boolean recoverConnection(String expectedId,Instant at){
        if(expectedId==null||!expectedId.equals(connectionFault))return false;
        recover(expectedId,at);connectionFault=null;return true;
    }
    public synchronized void queueGap(long stock,String cause,long discarded,String continuity,Instant at){
        var id=queueFaults.get(stock);
        if(id==null && (!cause.equals("QUEUE_OVERFLOW")||discarded==0))return;
        if(id==null){id=UUID.randomUUID().toString();queueFaults.put(stock,id);
            faults.put(id,new MonitorFault(id,"QUEUE",stock,"QUEUE_OVERFLOW",at,null,discarded,1,continuity));
        }else{var old=faults.get(id);faults.put(id,new MonitorFault(id,old.scope(),stock,old.reason(),old.startedAt(),null,
                old.discarded()+discarded,old.revision()+1,continuity));}
    }
    public synchronized void queueAccepted(long stock,String continuity,Instant at){
        var id=queueFaults.get(stock);if(id==null)return;var old=faults.get(id);
        // A restored process has a new lane UUID; the first newly committed baseline establishes recovery.
        if(old.continuity()!=null&&!old.continuity().equals(continuity))return;
        recover(id,at);queueFaults.remove(stock);
    }
    private void recover(String id,Instant at){
        var old=faults.get(id);faults.put(id,new MonitorFault(id,old.scope(),old.stockId(),old.reason(),old.startedAt(),
                at.isBefore(old.startedAt())?old.startedAt():at,old.discarded(),old.revision()+1,old.continuity()));
    }
    public synchronized void acknowledgeFault(String id,long revision){
        var current=faults.get(id);
        if(current!=null&&current.recoveredAt()!=null&&current.revision()==revision)faults.remove(id);
    }
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
        if(!code.startsWith("STOP_")&&connectionFault==null){
            connectionFault=UUID.randomUUID().toString();
            faults.put(connectionFault,new MonitorFault(connectionFault,"CONNECTION",null,code,at,null,0,1,null));
        }
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
