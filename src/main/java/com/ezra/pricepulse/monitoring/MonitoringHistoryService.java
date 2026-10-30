package com.ezra.pricepulse.monitoring;

import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Explicit cache preparation only. GET timeline has no dependency on this worker. */
@Service
@Profile("!standalone & !mq-it")
public class MonitoringHistoryService {
    public interface Source {AlpacaDataClient.Download download(Map<String,Long> stocks,Instant from,Instant to)throws Exception;}
    public record Status(String requestId,long stockId,String state,String errorCode){}
    private static final class Work {
        final String id=UUID.randomUUID().toString();final long stock;final Instant began;
        final Set<Long> viewers=ConcurrentHashMap.newKeySet();volatile String state="PENDING",error;
        Work(long stock,Instant began){this.stock=stock;this.began=began;}
        Status view(){return new Status(id,stock,state,error);}
    }
    private final MonitoringMapper subscriptions;
    private final StockMapper stocks;
    private final MarketDataMapper data;
    private final MarketHistoryCache cache;
    private final Source source;
    private final Clock clock;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofPlatform().daemon().name("history-prepare-",0).factory());
    private final Map<Long,Work> jobs=new HashMap<>();
    @Autowired public MonitoringHistoryService(MonitoringMapper subscriptions,StockMapper stocks,MarketDataMapper data,MarketHistoryCache cache,
            AlpacaProperties properties,ObjectMapper json,Clock clock){
        this(subscriptions,stocks,data,cache,(ids,from,to)->AlpacaDataClient.authenticated(properties,json).download(properties,ids,from,to),clock);
    }
    public MonitoringHistoryService(MonitoringMapper subscriptions,StockMapper stocks,MarketDataMapper data,MarketHistoryCache cache,Source source,Clock clock){
        this.subscriptions=subscriptions;this.stocks=stocks;this.data=data;this.cache=cache;this.source=source;this.clock=clock;
    }
    public synchronized Status request(long user,long stockId){
        requireOwner(user,stockId);Instant now=clock.instant();
        jobs.entrySet().removeIf(e->!e.getValue().state.equals("PENDING")&&e.getValue().began.isBefore(now.minusSeconds(600)));
        Work old=jobs.get(stockId);if(old!=null&&(old.state.equals("PENDING")||!old.began.isBefore(now.minusSeconds(300)))){old.viewers.add(user);return old.view();}
        if(jobs.size()>=256)throw unavailable();
        var stock=stocks.findActiveById(stockId);if(stock==null||!stock.market().equals("US"))throw missing();
        Work work=new Work(stockId,now);work.viewers.add(user);jobs.put(stockId,work);
        Instant from=now.atZone(MarketTime.NEW_YORK).minusMonths(1).minusDays(8).toInstant();
        var coverage=data.coverage(stockId);
        if(coverage!=null&&!MarketTime.instant(coverage.requestedFrom()).isAfter(from)
                &&!MarketTime.instant(coverage.requestedTo()).isBefore(now.minusSeconds(300))){work.state="READY";return work.view();}
        try {workers.execute(()->{
            try {var result=source.download(Map.of(stock.symbol(),stockId),from,now);cache.store(result,List.of(stockId),from,now);work.state="READY";}
            catch(Exception ignored){work.error="HISTORY_PREPARATION_FAILED";work.state="FAILED";}
        });}catch(RejectedExecutionException ignored){jobs.remove(stockId);throw unavailable();}
        return work.view();
    }
    public synchronized Status status(long user,String id){
        Work work=jobs.values().stream().filter(j->j.id.equals(id)&&j.viewers.contains(user)).findFirst().orElseThrow(MonitoringHistoryService::missing);
        requireOwner(user,work.stock);return work.view();
    }
    private void requireOwner(long user,long stock){var s=subscriptions.subscription(user,stock);if(s==null||!s.desired())throw missing();}
    private static BusinessException missing(){return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"历史准备请求不存在");}
    private static BusinessException unavailable(){return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"历史准备繁忙，请稍后重试");}
    @PreDestroy public void close(){workers.shutdownNow();}
}
