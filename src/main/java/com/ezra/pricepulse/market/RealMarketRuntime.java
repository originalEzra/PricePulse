package com.ezra.pricepulse.market;

import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Fixed small universe, one upstream instance. The WS callback never performs a database transaction. */
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
    private final Map<String,MarketQuoteLane> lanes=new ConcurrentHashMap<>();
    private final AtomicReference<List<MarketDataModels.Session>> sessions=new AtomicReference<>(List.of());
    private ScheduledExecutorService scheduler;
    private AlpacaStream stream;
    private AlpacaDataClient client;
    private Map<String,Long> stockIds;
    private volatile boolean stopped;
    public RealMarketRuntime(AlpacaProperties properties,MarketDataMapper data,StockMapper stocks,
            MarketHistoryCache cache,QuoteIngestionService ingestion,MarketFeedHealth health,ObjectMapper json,Clock clock) {
        this.properties=properties;this.data=data;this.stocks=stocks;this.cache=cache;this.ingestion=ingestion;
        this.health=health;this.json=json;this.clock=clock;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled()) return;
        properties.validate();
        stockIds=new LinkedHashMap<>();
        for (String symbol:properties.getSymbols()) {
            var matches=stocks.findActiveBySymbol(symbol,"US");
            if (matches.size()!=1 || !matches.getFirst().currency().equals("USD"))
                throw new IllegalStateException("Configured USD stock is not in the US catalog");
            stockIds.put(symbol,matches.getFirst().id());
            if (properties.isBusinessWritesEnabled()) {
                long stockId=matches.getFirst().id();
                lanes.put(symbol,new MarketQuoteLane(stockId,properties.getQueueCapacity(),work->process(stockId,work),code->{
                    if(code.equals("QUEUE_OVERFLOW"))health.overflow();else health.error(code);
                }));
            }
        }
        sessions.set(data.sessions());
        scheduler=Executors.newScheduledThreadPool(3,Thread.ofPlatform().daemon().name("alpaca-io-",0).factory());
        client=AlpacaDataClient.authenticated(properties,json);
        stream=new AlpacaStream(properties,json,scheduler,AlpacaStream.connector(HttpClient.newHttpClient()),health,
                this::message,()->lanes.values().forEach(l->l.gap("RECONNECT")),clock);
        // Cache warmup is independent of stream availability and formal business writes.
        scheduler.scheduleWithFixedDelay(this::syncHistory,0,300,TimeUnit.SECONDS);
        stream.start();
    }
    private void syncHistory() {
        try {
            Instant to=clock.instant(),from=to.atZone(MarketTime.NEW_YORK).minusMonths(1).minusDays(8).toInstant();
            var download=client.download(properties,stockIds,from,to);
            if (stopped) return;
            cache.store(download,stockIds.values(),from,to); sessions.set(download.sessions());
        } catch (Exception ignored) { health.error("HISTORY_SYNC_FAILED"); }
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
    @PreDestroy public void stop() {
        stopped=true;
        if(stream!=null)stream.close();
        if(scheduler!=null)scheduler.shutdownNow();
        lanes.values().forEach(MarketQuoteLane::close);
    }
}
