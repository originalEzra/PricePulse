package com.ezra.pricepulse.market;

import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.alert.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual disposable MySQL/Redis transactions; excluded from performance sampling. */
final class PipelineCorrectness {
    final RealPipelineExperiment p;
    PipelineCorrectness(RealPipelineExperiment p){this.p=p;}
    void run()throws Exception {
        identities();rollbackAndIndexRecovery();workerFailure();overloadRecovery();parallelStocks();concurrentRules();bulkAtomicity();databaseCounterProbe();
    }
    void identities()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.FEW_HIT,1);
        ingest(candidate("identity-base",1,"100","generation-a",p.clock.instant()));
        var up=candidate("identity-up",1,"100.10","generation-a",p.clock.instant());
        require(ingest(up).matching().triggered()==1,"first crossing");
        require(ingest(up).outcome()==QuoteOutcome.DUPLICATE,"trade identity dedup");
        require(ingest(candidate("identity-older",1,"99.90","generation-a",up.eventTime().toInstant(ZoneOffset.UTC).minusNanos(1))).outcome()==QuoteOutcome.DISCARDED_OUT_OF_ORDER,"out of order");
        require(ingest(candidate("identity-stale",1,"99.90","generation-a",p.clock.instant().minusSeconds(121))).outcome()==QuoteOutcome.DISCARDED_STALE,"stale quote");
        require(ingest(candidate("identity-future",1,"99.90","generation-a",p.clock.instant().plusSeconds(31))).outcome()==QuoteOutcome.DISCARDED_FUTURE,"future quote");
        String payload=p.jdbc.queryForObject("SELECT payload FROM outbox_events",String.class);
        p.notifications.consume(payload);p.notifications.consume(payload);
        require(count("notifications")==1&&count("alert_triggers")==1&&count("outbox_events")==1,"duplicate notification");
        p.checks.add(Map.of("name","duplicate-order-stale-future-and-consumer-idempotency","pass",true,"uniqueNotification",1));
    }
    void rollbackAndIndexRecovery()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.FEW_HIT,1);
        ingest(candidate("rollback-base",1,"100","generation-a",p.clock.instant()));
        var before=p.facts();String token=p.jdbc.queryForObject("SELECT rule_index_token FROM stocks WHERE id=1",String.class);
        var up=candidate("rollback-up",1,"100.10","generation-a",p.clock.instant());
        new TransactionTemplate(p.tx).execute(status->{require(ingest(up).matching().triggered()==1,"tentative trigger");status.setRollbackOnly();return null;});
        require(p.facts().equals(before),"atomic quote/rule/trigger/outbox rollback");
        require(token.equals(p.jdbc.queryForObject("SELECT rule_index_token FROM stocks WHERE id=1",String.class)),"token rollback");
        long rebuilds=p.meters.get("pricepulse.rules.rebuild").timer().count();
        require(ingest(up).matching().triggered()==1,"retry after token mismatch");
        require(p.meters.get("pricepulse.rules.rebuild").timer().count()>rebuilds,"derived token forces rebuild");
        p.redis.delete(RuleIndex.key(1));rebuilds=p.meters.get("pricepulse.rules.rebuild").timer().count();
        ingest(candidate("missing-index",1,"100.09","generation-a",p.clock.instant()));
        require(p.meters.get("pricepulse.rules.rebuild").timer().count()>rebuilds,"missing index rebuilt");
        require(count("alert_triggers")==1&&count("outbox_events")==1,"no duplicate after recovery");
        p.checks.add(Map.of("name","transaction-rollback-token-mismatch-and-missing-index","pass",true));
    }
    void workerFailure()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.FEW_HIT,1);
        p.jdbc.execute("CREATE TRIGGER bench_fail_outbox BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated fault injection'");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failed=new CountDownLatch(1);var recovered=new CountDownLatch(1);var base=new CountDownLatch(1);
        var faultCount=new AtomicInteger();List<String> discarded=new CopyOnWriteArrayList<>();
        try(var lane=new MarketQuoteLane(1,1024,w->{
            if(w.trade().eventKey().equals("worker-fail")){
                entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}
            }
            var t=w.trade();ingest(new QuoteCandidate(t.eventKey(),1,t.price(),MarketTime.utc(t.eventTime()),"ALPACA_IEX",null,t.receivedAt(),"ALPACA","iex",w.continuity()+":2026-10-30",w.reason()));
            if(t.eventKey().equals("worker-base"))base.countDown();if(t.eventKey().equals("worker-recovery"))recovered.countDown();
        },code->{faultCount.incrementAndGet();failed.countDown();},new MarketQuoteLane.Observer(){
            public void discarded(MarketQuoteLane.Work w,String why){discarded.add(w.trade().eventKey()+":"+why);}
        })){
            offer(lane,"worker-base","100");require(base.await(3,TimeUnit.SECONDS),"initial baseline");
            offer(lane,"worker-fail","100.10");require(entered.await(3,TimeUnit.SECONDS),"fault entered");
            offer(lane,"worker-uncertain-tail","99.90");release.countDown();require(failed.await(5,TimeUnit.SECONDS),"failure detected");
            p.jdbc.execute("DROP TRIGGER bench_fail_outbox");
            offer(lane,"worker-recovery","100.10");require(recovered.await(5,TimeUnit.SECONDS),"lane recovery");
            require(count("alert_triggers")==0&&count("outbox_events")==0,"no cross-failure alert");
            require(p.jdbc.queryForObject("SELECT COUNT(*) FROM quote_events WHERE event_key IN ('worker-fail','worker-uncertain-tail')",Long.class)==0,"failed and discarded tail not persisted");
            require("PROCESSING_FAILURE".equals(p.jdbc.queryForObject("SELECT baseline_reason FROM quote_events WHERE event_key='worker-recovery'",String.class)),"recovery is a fresh baseline");
            require(faultCount.get()==1&&discarded.equals(List.of("worker-uncertain-tail:PROCESSING_FAILURE")),"exact failure/discard count");
        }finally{p.jdbc.execute("DROP TRIGGER IF EXISTS bench_fail_outbox");}
        p.checks.add(Map.of("name","actual-mysql-error-worker-gap-tail-discard-and-recovery-baseline","pass",true));
    }
    void parallelStocks()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.ZERO_HIT,2);
        ingest(candidate("parallel-base-a",1,"100","a",p.clock.instant()));ingest(candidate("parallel-base-b",2,"100","b",p.clock.instant()));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);
        var sample=new PipelineProbe.Sample("lock-test",0,0,true,System.nanoTime(),System.nanoTime());
        try(var workers=Executors.newFixedThreadPool(3)){
            var holder=workers.submit(()->new TransactionTemplate(p.tx).execute(status->{
                p.stocks.findActiveByIdForUpdate(1);locked.countDown();
                try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}return null;
            }));require(locked.await(3,TimeUnit.SECONDS),"stock lock held");
            var blocked=workers.submit(()->{PipelineProbe.CURRENT.set(sample);attempted.countDown();try{return ingest(candidate("parallel-a",1,"100.01","a",p.clock.instant()));}finally{PipelineProbe.CURRENT.remove();}});
            require(attempted.await(3,TimeUnit.SECONDS),"contending ingestion attempted");
            long start=System.nanoTime();var other=workers.submit(()->ingest(candidate("parallel-b",2,"100.01","b",p.clock.instant())));
            require(other.get(2,TimeUnit.SECONDS).outcome()==QuoteOutcome.PROCESSED,"unrelated stock can commit");
            long otherElapsed=System.nanoTime()-start;
            // A deliberate lock hold belongs only to this correctness check, never a latency baseline.
            Thread.sleep(120);require(!blocked.isDone(),"same stock waits for lock");release.countDown();holder.get(3,TimeUnit.SECONDS);blocked.get(3,TimeUnit.SECONDS);
            if(PipelineProbe.detailed)require(sample.phases.getOrDefault("stock_lock_wait",0L)>TimeUnit.MILLISECONDS.toNanos(100),"lock SQL timer sees real contention");
            require(count("market_stream_state")==2&&count("alert_triggers")==0,"stock isolation");
            p.checks.add(Map.of("name","same-stock-lock-wait-and-cross-stock-parallelism","pass",true,"lockCallMs",sample.phases.getOrDefault("stock_lock_wait",0L)/1e6,"otherStockCommitMs",otherElapsed/1e6));
        }finally{release.countDown();}
    }
    void overloadRecovery()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.FEW_HIT,1);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var baseline=new CountDownLatch(1);var normal=new CountDownLatch(20);
        var discarded=new AtomicInteger();var overflows=new AtomicInteger();var faultAt=new AtomicLong();var baselineAt=new AtomicLong();
        try(var lane=new MarketQuoteLane(1,1024,w->{
            var t=w.trade();if(t.eventKey().equals("overload-first")){
                entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}
            }
            ingest(new QuoteCandidate(t.eventKey(),1,t.price(),MarketTime.utc(t.eventTime()),"ALPACA_IEX",null,t.receivedAt(),"ALPACA","iex",w.continuity()+":2026-10-30",w.reason()));
            if(t.eventKey().equals("overload-baseline")){baselineAt.set(System.nanoTime());baseline.countDown();}
            if(t.eventKey().startsWith("normal-"))normal.countDown();
        },code->{require(code.equals("QUEUE_OVERFLOW"),"expected overload fault only");overflows.incrementAndGet();faultAt.set(System.nanoTime());},new MarketQuoteLane.Observer(){
            public void discarded(MarketQuoteLane.Work w,String reason){discarded.incrementAndGet();}
        })){
            offer(lane,"overload-first","100");require(entered.await(3,TimeUnit.SECONDS),"blocked worker entered");
            for(int i=0;i<1024;i++)offer(lane,"uncertain-"+i,"100.10");
            require(lane.sample().depth()==1024,"original queue capacity reached");offer(lane,"overload-baseline","100.10");
            release.countDown();require(baseline.await(5,TimeUnit.SECONDS),"post-overflow baseline committed");
            RealPipelineExperiment.awaitDrain(Map.of(0,lane),5);long drainedAt=System.nanoTime();
            require(discarded.get()==1024&&overflows.get()==1,"actual full tail discarded once");
            require(count("alert_triggers")==0,"no alert across discarded tail");
            require("QUEUE_OVERFLOW".equals(p.jdbc.queryForObject("SELECT baseline_reason FROM quote_events WHERE event_key='overload-baseline'",String.class)),"persisted overload baseline");
            long normalStart=System.nanoTime();
            for(int i=0;i<20;i++){offer(lane,"normal-"+i,"99.90");Thread.sleep(20);}
            require(normal.await(3,TimeUnit.SECONDS),"lower-rate traffic completed");RealPipelineExperiment.awaitDrain(Map.of(0,lane),5);
            require(overflows.get()==1&&count("quote_events")==22&&count("alert_triggers")==1&&count("outbox_events")==1,"normal processing resumes without duplicate or lost follow-up");
            p.checks.add(Map.of("name","forced-1024-queue-overflow-and-lower-rate-recovery","pass",true,
                    "discarded",discarded.get(),"faultToCommittedBaselineMs",(baselineAt.get()-faultAt.get())/1e6,
                    "faultToQueueEmptyMs",(drainedAt-faultAt.get())/1e6,"normalEvents",20,
                    "observedFollowupRatePerSecond",20/((System.nanoTime()-normalStart)/1e9),"timingScope","forced worker hold; not a sustained capacity benchmark"));
        }finally{release.countDown();}
    }
    void offer(MarketQuoteLane lane,String key,String price){lane.offer(new AlpacaProtocol.Trade("PPTEST0",key,new BigDecimal(price),p.clock.instant(),p.clock.instant()));}
    void concurrentRules()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.FEW_HIT,1);
        ingest(candidate("concurrent-base",1,"100","concurrent",p.clock.instant()));
        var access=new com.ezra.pricepulse.monitoring.MonitoringAccess(new com.ezra.pricepulse.monitoring.MonitoringProperties(),p.sessions.getMapper(com.ezra.pricepulse.monitoring.MonitoringMapper.class),null);
        var lifecycle=PipelineProbe.transactional(new AlertRuleService(p.ruleMapper,p.stocks,p.sessions.getMapper(com.ezra.pricepulse.user.UserMapper.class),p.index,access),p.tx,false);
        var barrier=new CyclicBarrier(2);int events=120;
        try(var workers=Executors.newFixedThreadPool(2)){
            var market=workers.submit(()->{barrier.await();for(int i=0;i<events;i++)ingest(candidate("concurrent-"+i,1,i%2==0?"99.90":"100.10","concurrent",p.clock.instant()));return true;});
            var edits=workers.submit(()->{
                barrier.await();
                for(int i=0;i<10;i++){
                    var request=new SaveAlertRuleRequest(1L,"concurrent lifecycle "+i,null,AlertDirection.ABOVE,new BigDecimal("150"),AlertTriggerMode.REPEAT,new BigDecimal("149"));
                    var rule=lifecycle.create(1,request);
                    rule=lifecycle.update(1,rule.id(),new UpdateAlertRuleRequest(rule.version(),new SaveAlertRuleRequest(1L,"edited "+i,null,AlertDirection.ABOVE,new BigDecimal("160"),AlertTriggerMode.REPEAT,new BigDecimal("159"))));
                    rule=lifecycle.pause(1,rule.id(),rule.version());rule=lifecycle.resume(1,rule.id(),rule.version());lifecycle.delete(1,rule.id(),rule.version());
                }
                return true;
            });
            require(market.get(30,TimeUnit.SECONDS)&&edits.get(30,TimeUnit.SECONDS),"concurrent market and lifecycle finish");
        }
        require(count("alert_triggers")==events&&count("outbox_events")==events,"nearby repeat rules never lost during token changes");
        require(p.jdbc.queryForObject("SELECT COUNT(*) FROM alert_rules WHERE name LIKE 'edited %' AND status='DELETED'",Long.class)==10,"all user lifecycle transitions preserved");
        require(p.jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id NOT IN (1,2)",Long.class)==0,"far lifecycle rules cannot trigger");
        p.checks.add(Map.of("name","production-rule-create-edit-pause-resume-delete-concurrent-with-ingestion","pass",true,"quoteEvents",events,"lifecycleTransactions",50,"triggers",events));
    }
    void databaseCounterProbe()throws Exception {
        long before=digestProbeCount();int threads=15,perThread=200;
        try(var workers=Executors.newFixedThreadPool(threads)){
            var ready=new CountDownLatch(threads);var start=new CountDownLatch(1);List<Future<Integer>> futures=new ArrayList<>();
            for(int n=0;n<threads;n++)futures.add(workers.submit(()->{
                ready.countDown();start.await();int checked=0;
                try(var c=p.pool.getConnection();var statement=c.prepareStatement("SELECT 1 AS pipeline_counter_probe")){
                    for(int i=0;i<perThread;i++)try(var rows=statement.executeQuery()){require(rows.next()&&rows.getInt(1)==1,"counter probe result");checked++;}
                }
                return checked;
            }));
            require(ready.await(3,TimeUnit.SECONDS),"counter workers ready");start.countDown();int actual=0;
            for(var f:futures)actual+=f.get(15,TimeUnit.SECONDS);
            long database=digestProbeCount()-before;
            require(actual==threads*perThread,"exact SQL completion count");
            p.checks.add(Map.of("name","performance-schema-concurrent-counter-control","executedSql",actual,"successfulRows",actual,"databaseDigestCount",database,"difference",database-actual,"exactDigestAgreement",database==actual));
        }
    }
    void bulkAtomicity()throws Exception {
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.BURST_1000,1);
        ingest(candidate("bulk-base",1,"100","bulk",p.clock.instant()));var before=p.facts();
        p.jdbc.execute("CREATE TRIGGER bench_bulk_fail BEFORE INSERT ON outbox_events FOR EACH ROW BEGIN IF NEW.aggregate_id > 500 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated second page failure'; END IF; END");
        var up=candidate("bulk-up",1,"110","bulk",p.clock.instant());boolean failed=false;
        try{ingest(up);}catch(RuntimeException expected){failed=true;}finally{p.jdbc.execute("DROP TRIGGER bench_bulk_fail");}
        require(failed&&p.facts().equals(before),"second page failure rolls back all earlier trigger/outbox rows");
        require(p.jdbc.queryForObject("SELECT COUNT(*) FROM alert_rules WHERE status='ACTIVE' AND version=0",Long.class)==1000,"all rule transitions roll back");
        require(ingest(up).matching().triggered()==1000,"same identity can safely retry the rolled back transaction");
        require(ingest(up).outcome()==QuoteOutcome.DUPLICATE,"committed identity cannot duplicate the bulk");
        require(count("outbox_events")==1000&&count("alert_triggers")==1000,"one Outbox per trigger");
        require(p.jdbc.queryForObject("SELECT COUNT(*) FROM alert_rules WHERE status='COMPLETED' AND version=1",Long.class)==1000,"every ONCE rule completes exactly once");
        var payloads=p.jdbc.queryForList("SELECT payload FROM outbox_events ORDER BY id",String.class);
        p.notifications.consume(payloads.getFirst());p.notifications.consume(payloads.getFirst());p.notifications.consume(payloads.getLast());
        require(count("notifications")==2,"bulk messages retain consumer idempotency");
        p.checks.add(Map.of("name","thousand-trigger-bounded-outbox-rollback-safe-retry-and-idempotency","pass",true,"triggers",1000,"outboxRows",1000));
        p.jdbc.update("INSERT INTO quote_events(stock_id,event_key,price,event_time,source,origin_type) VALUES(1,'bulk-legacy-fixture',110,'2026-10-30 14:10:00','FIXTURE','LEGACY')");
        p.jdbc.update("INSERT INTO alert_triggers(trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot) SELECT 'legacy-bulk-guard',1,id,110,'2026-10-30 14:10:00','{}' FROM quote_events WHERE event_key='bulk-legacy-fixture'");
        long legacy=p.jdbc.queryForObject("SELECT id FROM alert_triggers WHERE trigger_key='legacy-bulk-guard'",Long.class);
        long real=p.json.readTree(payloads.getFirst()).get("triggerId").asLong();
        p.jdbc.update("DELETE FROM outbox_events WHERE aggregate_id=?",real);long outboxBefore=count("outbox_events");
        boolean guarded=false;
        try{new TransactionTemplate(p.tx).execute(status->{
            p.outboxWriter.alertTriggeredBatch(List.of(new com.ezra.pricepulse.outbox.OutboxWriter.PendingAlert(real,1,1,1),new com.ezra.pricepulse.outbox.OutboxWriter.PendingAlert(legacy,1,1,1)));return null;
        });}catch(RuntimeException expected){guarded=true;}
        require(guarded&&count("outbox_events")==outboxBefore,"a mixed REAL/LEGACY batch cannot partially commit");
        require(p.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id IN (?,?)",Long.class,real,legacy)==0,"no guarded row escapes rollback");
        p.checks.add(Map.of("name","mixed-real-legacy-outbox-batch-is-rejected-atomically","pass",true));
    }
    long digestProbeCount(){return p.jdbc.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() AND DIGEST_TEXT LIKE 'SELECT % AS %pipeline_counter_probe%'",Long.class);}
    QuoteCandidate candidate(String key,long stock,String price,String continuity,Instant at){return new QuoteCandidate(key,stock,new BigDecimal(price),MarketTime.utc(at),"ALPACA_IEX",null,p.clock.instant(),"ALPACA","iex",continuity+":2026-10-30","STARTUP");}
    QuoteResult ingest(QuoteCandidate c){return p.ingestion.ingest(c,QuoteIngestionPolicy.LIVE);}
    long count(String table){return p.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    static void require(boolean condition,String label){if(!condition)throw new IllegalStateException("Correctness failed: "+label);}
}
