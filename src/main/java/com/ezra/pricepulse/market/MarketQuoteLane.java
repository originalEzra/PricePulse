package com.ezra.pricepulse.market;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded, ordered lane. Overload/failure discards the uncertain tail and establishes a fresh baseline. */
final class MarketQuoteLane implements AutoCloseable {
    record Work(AlpacaProtocol.Trade trade,String continuity,String reason) {}
    private final ThreadPoolExecutor worker;
    private final Consumer<Work> process;
    private final Consumer<String> fault;
    private String continuity=UUID.randomUUID().toString(),reason="STARTUP";
    private boolean stopped;
    MarketQuoteLane(long stockId,int capacity,Consumer<Work> process,Consumer<String> fault) {
        this.process=process;this.fault=fault;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon().name("market-stock-"+stockId).factory());
    }
    synchronized void gap(String cause) {
        continuity=UUID.randomUUID().toString();reason=cause;worker.getQueue().clear();
    }
    synchronized void offer(AlpacaProtocol.Trade trade) {
        if(stopped)return;
        try{enqueue(trade);}catch(RejectedExecutionException ignored){gap("QUEUE_OVERFLOW");fault.accept("QUEUE_OVERFLOW");enqueue(trade);}
    }
    private void enqueue(AlpacaProtocol.Trade trade) {
        var work=new Work(trade,continuity,reason);
        worker.execute(()->{
            try{process.accept(work);}catch(RuntimeException ignored){gap("PROCESSING_FAILURE");fault.accept("PROCESSING_FAILED_GAP");}
        });
    }
    @Override public synchronized void close(){stopped=true;worker.shutdownNow();}
}
