package com.ezra.pricepulse.market;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded, ordered lane. Overload/failure discards the uncertain tail and establishes a fresh baseline. */
final class MarketQuoteLane implements AutoCloseable {
    record Work(AlpacaProtocol.Trade trade,String continuity,String reason) {}
    interface Observer {
        Observer NONE = new Observer() { };
        default void offered(AlpacaProtocol.Trade trade) { }
        default void enqueued(Work work, long at) { }
        default void started(Work work, long enqueuedAt, long startedAt) { }
        default void completed(Work work, long startedAt, boolean success) { }
        default void discarded(Work work, String cause) { }
        default void invalidated(String cause,long discarded,String continuity) { }
    }
    record QueueSample(int depth, long oldestWaitNanos, int active) { }
    private final ThreadPoolExecutor worker;
    private final Consumer<Work> process;
    private final Consumer<String> fault;
    private final Observer observer;
    private String continuity=UUID.randomUUID().toString(),reason="STARTUP";
    private boolean stopped;
    MarketQuoteLane(long stockId,int capacity,Consumer<Work> process,Consumer<String> fault) {
        this(stockId,capacity,process,fault,Observer.NONE);
    }
    MarketQuoteLane(long stockId,int capacity,Consumer<Work> process,Consumer<String> fault,Observer observer) {
        this.process=process;this.fault=fault;
        this.observer=observer;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon().name("market-stock-"+stockId).factory());
    }
    synchronized void gap(String cause) {
        continuity=UUID.randomUUID().toString();reason=cause;
        if(observer==Observer.NONE)worker.getQueue().clear();
        else {
            var discarded=new java.util.ArrayList<Runnable>();worker.getQueue().drainTo(discarded);
            for(var task:discarded)observe(()->observer.discarded(((Task)task).work,cause));
            observe(()->observer.invalidated(cause,discarded.size(),continuity));
        }
    }
    synchronized void offer(AlpacaProtocol.Trade trade) {
        if(stopped)return;
        observe(()->observer.offered(trade));
        try{enqueue(trade);}catch(RejectedExecutionException ignored){gap("QUEUE_OVERFLOW");fault.accept("QUEUE_OVERFLOW");enqueue(trade);}
    }
    private void enqueue(AlpacaProtocol.Trade trade) {
        var work=new Work(trade,continuity,reason);
        var task=new Task(work);
        worker.execute(task);
        observe(()->observer.enqueued(work,task.enqueuedAt));
    }
    private final class Task implements Runnable {
        final Work work;final long enqueuedAt=observer==Observer.NONE?0:System.nanoTime();
        Task(Work work){this.work=work;}
        public void run(){
            long start=observer==Observer.NONE?0:System.nanoTime();
            observe(()->observer.started(work,enqueuedAt,start));boolean success=false;
            try{process.accept(work);success=true;}
            catch(RuntimeException ignored){gap("PROCESSING_FAILURE");fault.accept("PROCESSING_FAILED_GAP");}
            finally{boolean completed=success;observe(()->observer.completed(work,start,completed));}
        }
    }
    QueueSample sample(){
        var first=worker.getQueue().peek();
        long oldest=first instanceof Task task && task.enqueuedAt!=0?Math.max(0,System.nanoTime()-task.enqueuedAt):0;
        return new QueueSample(worker.getQueue().size(),oldest,worker.getActiveCount());
    }
    synchronized String continuityId(){return continuity;}
    private void observe(Runnable event){if(observer!=Observer.NONE)try{event.run();}catch(RuntimeException ignored){ }}
    @Override public synchronized void close(){
        stopped=true;var discarded=worker.shutdownNow();
        if(observer!=Observer.NONE)for(var task:discarded)observe(()->observer.discarded(((Task)task).work,"SHUTDOWN"));
    }
}
