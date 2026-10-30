package com.ezra.pricepulse.market;

import com.ezra.pricepulse.stock.StockMapper;
import com.ezra.pricepulse.monitoring.*;
import com.ezra.pricepulse.monitoring.MonitoringModels.Demand;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One upstream instance. Persistent demand may change; WS callbacks never access MySQL. */
@Component
@Profile("!standalone & !mq-it")
public class RealMarketRuntime {
    private final AlpacaProperties properties;
    private final MarketDataMapper data;
    private final StockMapper stocks;
    private final MarketHistoryCache cache;
    private final QuoteIngestionService ingestion;
    private final MarketFeedHealth health;
    private final ObjectMapper json;
    private final Clock clock;
    private final MonitoringProperties monitoring;
    private final MonitoringMapper subscriptions;
    private final AlpacaStream.Connector connectorOverride;
    private final Supplier<AlpacaDataClient> clientOverride;
    private final Map<String,MarketQuoteLane> lanes=new ConcurrentHashMap<>();
    private final AtomicReference<List<MarketDataModels.Session>> sessions=new AtomicReference<>(List.of());
    private ScheduledExecutorService scheduler;
    private AlpacaStream stream;
    private AlpacaDataClient client;
    private final Map<String,Long> stockIds=new ConcurrentHashMap<>(),intents=new ConcurrentHashMap<>();
    private final AtomicBoolean syncing=new AtomicBoolean();
    private volatile boolean stopped;
    @Autowired public RealMarketRuntime(AlpacaProperties properties,MarketDataMapper data,StockMapper stocks,
            MarketHistoryCache cache,QuoteIngestionService ingestion,MarketFeedHealth health,ObjectMapper json,Clock clock,
            MonitoringProperties monitoring,MonitoringMapper subscriptions) {
        this(properties,data,stocks,cache,ingestion,health,json,clock,monitoring,subscriptions,null,null);
    }
    RealMarketRuntime(AlpacaProperties properties,MarketDataMapper data,StockMapper stocks,
            MarketHistoryCache cache,QuoteIngestionService ingestion,MarketFeedHealth health,ObjectMapper json,Clock clock,
            MonitoringProperties monitoring,MonitoringMapper subscriptions,AlpacaStream.Connector connectorOverride,Supplier<AlpacaDataClient> clientOverride) {
        this.properties=properties;this.data=data;this.stocks=stocks;this.cache=cache;this.ingestion=ingestion;
        this.health=health;this.json=json;this.clock=clock;
        this.monitoring=monitoring;this.subscriptions=subscriptions;this.connectorOverride=connectorOverride;this.clientOverride=clientOverride;
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!properties.isEnabled()||stream!=null||stopped) return;
        properties.validate();
        var initial=new ArrayList<Demand>();
        if(monitoring.isDynamicSubscriptionsEnabled())initial.addAll(subscriptions.liveDemand());
        else for (String symbol:properties.getSymbols()) {
            var matches=stocks.findActiveBySymbol(symbol,"US");
            if (matches.size()!=1 || !matches.getFirst().currency().equals("USD"))
                throw new IllegalStateException("Configured USD stock is not in the US catalog");
            initial.add(new Demand(matches.getFirst().id(),symbol,0));
        }
        install(initial);
        sessions.set(data.sessions());
        scheduler=Executors.newScheduledThreadPool(3,Thread.ofPlatform().daemon().name("alpaca-io-",0).factory());
        client=clientOverride==null?AlpacaDataClient.authenticated(properties,json):clientOverride.get();
        stream=new AlpacaStream(properties,json,scheduler,connectorOverride==null?AlpacaStream.connector(HttpClient.newHttpClient()):connectorOverride,health,
                this::message,()->lanes.values().forEach(l->l.gap("RECONNECT")),clock);
        if(monitoring.isDynamicSubscriptionsEnabled())stream.enableDynamicSubscriptions(Map.copyOf(intents));
        // Cache warmup is independent of stream availability and formal business writes.
        scheduler.scheduleWithFixedDelay(this::syncHistory,0,300,TimeUnit.SECONDS);
        stream.start();
    }
    public synchronized void applySubscriptions(List<Demand> desired){
        if(stopped||stream==null||!monitoring.isDynamicSubscriptionsEnabled())return;
        boolean changed=install(desired);
        stream.desireSubscriptions(Map.copyOf(intents));
        if(changed)scheduler.execute(this::syncHistory);
    }
    private boolean install(List<Demand> desired){
        var next=new LinkedHashMap<String,Demand>();
        for(var d:desired)if(next.put(d.symbol(),d)!=null)throw new IllegalStateException("Duplicate upstream symbol");
        boolean changed=false;
        for(String symbol:new ArrayList<>(stockIds.keySet())){
            var d=next.get(symbol);
            if(d==null||!Objects.equals(intents.get(symbol),d.intentVersion())||!Objects.equals(stockIds.get(symbol),d.stockId())){
                var lane=lanes.remove(symbol);if(lane!=null)lane.close();stockIds.remove(symbol);intents.remove(symbol);changed=true;
            }
        }
        for(var d:desired)if(!stockIds.containsKey(d.symbol())){
            stockIds.put(d.symbol(),d.stockId());intents.put(d.symbol(),d.intentVersion());changed=true;
            if(properties.isBusinessWritesEnabled())lanes.put(d.symbol(),new MarketQuoteLane(d.stockId(),properties.getQueueCapacity(),work->process(d.stockId(),work),code->{
                if(code.equals("QUEUE_OVERFLOW"))health.overflow();else health.error(code);
            }));
        }
        return changed;
    }
    public record SubscriptionObservation(String state,String epoch,String errorCode,long intentVersion,boolean absent){}
    public synchronized SubscriptionObservation subscription(String symbol){
        var view=stream==null?null:stream.subscriptions();
        if(view==null)return new SubscriptionObservation("PENDING",null,"AWAITING_UPSTREAM_CONFIRMATION",-1,false);
        long intent=view.desired().getOrDefault(symbol,-1L);
        var failure=view.failures().get(symbol);
        if(failure!=null&&failure.intent()==intent)return new SubscriptionObservation("FAILED",view.epoch(),failure.code(),intent,view.rejectedAbsent(symbol));
        if(health.connection().equals("REJECTED"))return new SubscriptionObservation("FAILED",null,"UPSTREAM_REJECTED",intent,false);
        boolean confirmed=view.confirms(symbol);
        return new SubscriptionObservation(confirmed?"CONFIRMED":"PENDING",confirmed?view.epoch():null,
                confirmed?null:"AWAITING_UPSTREAM_CONFIRMATION",intent,view.absent(symbol));
    }
    private void syncHistory() {
        if(stopped||!syncing.compareAndSet(false,true))return;
        try {
            Instant to=clock.instant(),from=to.atZone(MarketTime.NEW_YORK).minusMonths(1).minusDays(8).toInstant();
            var snapshot=new TreeMap<>(stockIds);var entries=new ArrayList<>(snapshot.entrySet());
            for(int offset=0;offset<entries.size();offset+=MarketUniverse.MAX_SYMBOLS){
                var chunk=new LinkedHashMap<String,Long>();entries.subList(offset,Math.min(entries.size(),offset+MarketUniverse.MAX_SYMBOLS)).forEach(e->chunk.put(e.getKey(),e.getValue()));
                var download=client.download(properties,chunk,from,to);
                if (stopped) return;
                cache.store(download,chunk.values(),from,to); sessions.set(download.sessions());
            }
        } catch (Exception ignored) { health.error("HISTORY_SYNC_FAILED"); }
        finally{syncing.set(false);}
    }
    private void message(JsonNode item,Instant received) {
        if (!"t".equals(item.path("T").asText())) {
            health.reject(); health.error("CORRECTION_OR_CANCEL_GAP");
            MarketQuoteLane lane=lanes.get(item.path("S").asText()); if(lane!=null)lane.gap("CORRECTION_OR_CANCEL");
            return;
        }
        try {
            var trade=AlpacaProtocol.trade(item,received,stockIds.keySet());
            if (trade==null) { health.reject();return; }
            health.observed(trade.symbol(),trade.eventTime());
            var calendar=sessions.get();
            if (calendar.stream().noneMatch(s->s.contains(trade.eventTime()))) {
                health.reject();
                if (calendar.isEmpty() || trade.eventTime().isAfter(calendar.getLast().close())) health.error("CALENDAR_UNAVAILABLE");
                return;
            }
            MarketQuoteLane lane=lanes.get(trade.symbol());
            if (lane!=null) lane.offer(trade);
        } catch (RuntimeException ignored) {
            health.reject();health.error("INVALID_OR_UNSUPPORTED_TRADE");
            MarketQuoteLane lane=lanes.get(item.path("S").asText()); if(lane!=null)lane.gap("INVALID_TRADE");
        }
    }
    private void process(long stockId,MarketQuoteLane.Work work) {
        var trade=work.trade();
        var candidate=new QuoteCandidate(trade.eventKey(),stockId,trade.price(),
                LocalDateTime.ofInstant(trade.eventTime(),ZoneOffset.UTC),"ALPACA_IEX",null,trade.receivedAt(),
                "ALPACA","iex",work.continuity()+":"+trade.eventTime().atZone(MarketTime.NEW_YORK).toLocalDate(),work.reason());
        var result=ingestion.ingest(candidate,QuoteIngestionPolicy.LIVE);
        if (result.outcome()==QuoteOutcome.DISCARDED_STALE || result.outcome()==QuoteOutcome.DISCARDED_FUTURE) {
            health.reject();var lane=lanes.get(trade.symbol());if(lane!=null)lane.gap("STALE_OR_FUTURE");
        }
    }
    @PreDestroy public synchronized void stop() {
        stopped=true;
        if(stream!=null)stream.closeAndAwait();
        if(scheduler!=null)scheduler.shutdownNow();
        lanes.values().forEach(MarketQuoteLane::close);
    }
}
