package com.ezra.pricepulse.market;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Opt-in, bounded per-stock aggregation. No per-trade logging or event/user ID labels. */
@Component
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(prefix="price-pulse.market",name="telemetry-enabled",havingValue="true")
public class MarketLaneTelemetry implements AutoCloseable {
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Long,Series> series=new ConcurrentHashMap<>();
    private final ThreadLocal<Frame> current=new ThreadLocal<>();
    public MarketLaneTelemetry(MeterRegistry registry,Clock clock){this.registry=registry;this.clock=clock;}
    private Series series(long stock){return series.computeIfAbsent(stock,Series::new);}
    MarketQuoteLane.Observer observer(long stock){
        Series data;try{data=series(stock);}catch(RuntimeException ignored){return MarketQuoteLane.Observer.NONE;}
        return new MarketQuoteLane.Observer(){
            public void offered(AlpacaProtocol.Trade trade){data.input.increment();}
            public void started(MarketQuoteLane.Work work,long enqueued,long start){
                var frame=new Frame(data,work.trade(),start);frame.binding=QuoteTiming.bind(frame);current.set(frame);
                safe(()->data.wait.record(Math.max(0,start-enqueued),TimeUnit.NANOSECONDS));
            }
            public void completed(MarketQuoteLane.Work work,long start,boolean success){
                Frame frame=current.get();
                if(frame==null)return;
                try{
                    safe(()->{
                        data.process.record(System.nanoTime()-start,TimeUnit.NANOSECONDS);
                        if(!success)data.failed.increment();
                        long lag=Duration.between(frame.trade.receivedAt(),clock.instant()).toNanos();
                        if(lag>=0)data.lag.record(lag,TimeUnit.NANOSECONDS);else data.invalidClock.increment();
                        frame.phases.forEach((phase,nanos)->data.phases.get(phase).record(nanos,TimeUnit.NANOSECONDS));
                    });
                }finally{try{frame.binding.close();}catch(Exception ignored){ }current.remove();}
            }
            public void discarded(MarketQuoteLane.Work work,String cause){data.discard.increment();}
        };
    }
    void attach(long stock,Supplier<MarketQuoteLane.QueueSample> source){safe(()->series(stock).queue=source);}
    void fault(long stock,String code){if(code.equals("QUEUE_OVERFLOW"))safe(()->series(stock).overflow.increment());}
    QuoteResult ingest(long stock,Supplier<QuoteResult> call){
        Series data;try{data=series(stock);}catch(RuntimeException ignored){return call.get();}long start=System.nanoTime();
        try{
            QuoteResult result=call.get();
            safe(()->{if(result.outcome()==QuoteOutcome.PROCESSED)data.processed.increment();else data.rejected.increment();});
            return result;
        }finally{safe(()->data.transaction.record(System.nanoTime()-start,TimeUnit.NANOSECONDS));}
    }
    void detach(long stock){Series data=series.remove(stock);if(data!=null)data.handles.forEach(m->safe(()->registry.remove(m)));}
    @PreDestroy public void close(){new ArrayList<>(series.keySet()).forEach(this::detach);}
    private static void safe(Runnable observation){try{observation.run();}catch(RuntimeException ignored){ }}
    private final class Series {
        final List<Meter> handles=new ArrayList<>();final String stock;
        final Counter input,processed,rejected,failed,discard,overflow,invalidClock;
        final io.micrometer.core.instrument.Timer wait,process,lag,transaction;
        final Map<String,io.micrometer.core.instrument.Timer> phases=new HashMap<>();
        volatile Supplier<MarketQuoteLane.QueueSample> queue=()->new MarketQuoteLane.QueueSample(0,0,0);
        Series(long id){
            stock=Long.toString(id);input=counter("input");processed=counter("processed");rejected=counter("rejected");
            failed=counter("failed");discard=counter("discarded");overflow=counter("overflow");invalidClock=counter("invalid_clock");
            wait=timer("queue.wait","Monotonic enqueue-to-worker wait");process=timer("process","Worker handler duration, including commit");
            lag=timer("receive.lag","Receive Instant to handler completion; wall-clock dependent");
            transaction=timer("transaction","LIVE ingestion proxy including real commit; excludes queue wait");
            for(String phase:List.of("rule_decision","trigger_outbox_write"))phases.put(phase,timer("phase."+phase,"Accumulated invoked phase per trade"));
            handles.add(Gauge.builder("pricepulse.market.queue.depth",this,s->s.queue.get().depth()).tag("stock",stock).register(registry));
            handles.add(Gauge.builder("pricepulse.market.queue.oldest.wait",this,s->s.queue.get().oldestWaitNanos()/1e6).tag("stock",stock).baseUnit("milliseconds").register(registry));
        }
        Counter counter(String name){var meter=Counter.builder("pricepulse.market."+name).tag("stock",stock).register(registry);handles.add(meter);return meter;}
        io.micrometer.core.instrument.Timer timer(String name,String description){var meter=io.micrometer.core.instrument.Timer.builder("pricepulse.market."+name).tag("stock",stock).description(description).publishPercentiles(.5,.95,.99).register(registry);handles.add(meter);return meter;}
    }
    private static final class Frame implements QuoteTiming.Sink {
        final Series data;final AlpacaProtocol.Trade trade;final long start;final Map<String,Long> phases=new HashMap<>();AutoCloseable binding;
        Frame(Series data,AlpacaProtocol.Trade trade,long start){this.data=data;this.trade=trade;this.start=start;}
        public void record(String phase,long nanos){if(data.phases.containsKey(phase))phases.merge(phase,nanos,Long::sum);}
    }
}
