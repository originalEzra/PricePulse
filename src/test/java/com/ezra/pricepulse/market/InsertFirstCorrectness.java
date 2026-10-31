package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.error.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable MySQL / real MyBatis and Spring proxies. No provider or publisher is constructed. */
final class InsertFirstCorrectness {
    final RealPipelineExperiment p;
    InsertFirstCorrectness(RealPipelineExperiment p){this.p=p;}
    void run()throws Exception{
        identityAndConflicts();caughtDuplicateCanCommit();otherDatabaseFailures();concurrentServiceDuplicates();
        differentStockIdentityConflict();mapperWait(true);mapperWait(false);uniqueLockRollbackProbe();
    }
    QuoteCandidate c(String key,long stock,String price){return new QuoteCandidate(key,stock,new BigDecimal(price),
            MarketTime.utc(RealPipelineExperiment.EVENT_ORIGIN),"ALPACA_IEX",null,p.clock.instant(),"ALPACA","iex","dedup:2026-10-30","STARTUP");}
    QuoteResult ingest(QuoteCandidate c){return p.ingestion.ingest(c,QuoteIngestionPolicy.LIVE);}
    void reset()throws Exception{p.clock.reset();p.seed(RealPipelineExperiment.Scenario.FEW_HIT,1);}
    void require(boolean condition,String text){if(!condition)throw new IllegalStateException(text);}
    void pass(String name){p.checks.add(Map.of("name",name,"pass",true));}
    TransactionTemplate transaction(){var t=new TransactionTemplate(p.tx);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return t;}
    String facts()throws Exception{
        var all=new TreeMap<String,Object>();
        for(String table:List.of("stocks","quote_events","alert_rules","alert_triggers","outbox_events","notifications"))
            all.put(table,p.jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        all.put("stream",p.jdbc.queryForList("SELECT * FROM market_stream_state ORDER BY stock_id"));
        return RealPipelineExperiment.sha(p.json.writeValueAsBytes(all));
    }
    void conflict(QuoteCandidate c){try{ingest(c);throw new IllegalStateException("expected payload conflict");}
        catch(BusinessException e){require(e.errorCode()==ErrorCode.CONFLICT,"payload conflict code");}}
    void identityAndConflicts()throws Exception{
        reset();ingest(c("base",1,"100"));var original=c("cross",1,"100.10");
        require(ingest(original).matching().triggered()==1,"first valid crossing");String before=facts();
        require(ingest(original).outcome()==QuoteOutcome.DUPLICATE,"same payload duplicate");
        var variants=List.of(
                new QuoteCandidate("cross",1,new BigDecimal("101"),original.eventTime(),original.source(),null,original.receivedAt(),"ALPACA","iex",original.continuityId(),null),
                new QuoteCandidate("cross",1,original.price(),original.eventTime().plusNanos(1),original.source(),null,original.receivedAt(),"ALPACA","iex",original.continuityId(),null),
                new QuoteCandidate("cross",1,original.price(),original.eventTime(),"OTHER",null,original.receivedAt(),"ALPACA","iex",original.continuityId(),null),
                new QuoteCandidate("cross",1,original.price(),original.eventTime(),original.source(),1L,original.receivedAt(),"ALPACA","iex",original.continuityId(),null),
                new QuoteCandidate("cross",1,original.price(),original.eventTime(),original.source(),null,original.receivedAt(),"OTHER","iex",original.continuityId(),null),
                new QuoteCandidate("cross",1,original.price(),original.eventTime(),original.source(),null,original.receivedAt(),"ALPACA","other",original.continuityId(),null));
        for(var variant:variants)conflict(variant);
        require(facts().equals(before),"duplicates/conflicts cannot update quote, watermark, rule, trigger or outbox");
        p.jdbc.update("UPDATE quote_events SET origin_type='LEGACY' WHERE event_key='cross'");before=facts();conflict(original);
        require(facts().equals(before),"LEGACY cannot be treated as a REAL duplicate");
        pass("identity-price-nanosecond-source-sequence-provider-feed-and-origin-checks");

        reset();var stale=new QuoteCandidate("stale",1,new BigDecimal("100"),MarketTime.utc(RealPipelineExperiment.EVENT_ORIGIN.minusSeconds(121)),"ALPACA_IEX",null,p.clock.instant(),"ALPACA","iex","dedup:2026-10-30",null);
        require(ingest(stale).outcome()==QuoteOutcome.DISCARDED_STALE,"stale inserted and marked discarded");before=facts();
        require(ingest(stale).outcome()==QuoteOutcome.DUPLICATE&&facts().equals(before),"discarded identity stays idempotent");
        pass("discarded-event-status-is-not-reprocessed");
    }
    void caughtDuplicateCanCommit()throws Exception{
        reset();var first=c("base",1,"100");ingest(first);
        transaction().executeWithoutResult(status->{
            require(ingest(first).outcome()==QuoteOutcome.DUPLICATE,"caught duplicate returned");
            require(!status.isRollbackOnly(),"caught mapper exception did not poison Spring transaction");
            try{
                var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(p.tx.getDataSource());
                require(connection.getTransactionIsolation()==Connection.TRANSACTION_READ_COMMITTED,"READ_COMMITTED unchanged");
                try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT 1")){require(result.next()&&result.getInt(1)==1,"connection remains usable");}
            }catch(SQLException e){throw new IllegalStateException(e);}
            require(ingest(c("after-duplicate",1,"100.10")).matching().triggered()==1,"follow-up in same transaction");
        });
        require(p.facts().get("quote_events")==2&&p.facts().get("alert_triggers")==1&&p.facts().get("outbox_events")==1,"actual outer commit succeeded");
        pass("caught-1062-can-read-and-commit-followup-in-same-read-committed-transaction");
    }
    void otherDatabaseFailures()throws Exception{
        reset();ingest(c("base",1,"100"));String before=facts();
        p.jdbc.execute("ALTER TABLE quote_events ADD UNIQUE KEY bench_other_source(source)");
        try{try{ingest(c("other-key",1,"100.10"));throw new IllegalStateException("other unique key must fail");}
            catch(DuplicateKeyException expected){require(expected.getMostSpecificCause().getMessage().endsWith("for key 'quote_events.bench_other_source'"),"actual other key was propagated");}}
        finally{p.jdbc.execute("ALTER TABLE quote_events DROP INDEX bench_other_source");}
        require(facts().equals(before),"other unique constraint fully rolls back");
        p.jdbc.execute("CREATE TRIGGER bench_quote_fail BEFORE INSERT ON quote_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated statement failure'");
        try{try{ingest(c("retry",1,"100.10"));throw new IllegalStateException("statement must fail");}catch(DataAccessException expected){ }}
        finally{p.jdbc.execute("DROP TRIGGER bench_quote_fail");}
        require(facts().equals(before),"SQL error cannot leave received quote or business effects");
        require(ingest(c("retry",1,"100.10")).matching().triggered()==1,"same identity retries after rollback");
        require(ingest(c("retry",1,"100.10")).outcome()==QuoteOutcome.DUPLICATE,"successful retry remains idempotent");
        pass("unrelated-unique-and-sql-error-propagate-with-rollback-and-safe-retry");
    }
    void concurrentServiceDuplicates()throws Exception{
        reset();ingest(c("base",1,"100"));var input=c("race",1,"100.10");var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            var a=workers.submit(()->{start.await();return ingest(input).outcome();});
            var b=workers.submit(()->{start.await();return ingest(input).outcome();});start.countDown();
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            require(results.contains(QuoteOutcome.PROCESSED)&&results.contains(QuoteOutcome.DUPLICATE),"stock lock serializes identical callers");
        }
        require(p.facts().get("quote_events")==2&&p.facts().get("alert_triggers")==1&&p.facts().get("outbox_events")==1,"race produces one event/trigger/outbox");
        pass("two-service-threads-share-stock-lock-and-commit-one-trigger");
    }
    void differentStockIdentityConflict()throws Exception{
        p.clock.reset();p.seed(RealPipelineExperiment.Scenario.ZERO_RULES,5);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            List<Future<String>> jobs=new ArrayList<>();
            for(int stock=1;stock<=2;stock++){long id=stock;jobs.add(workers.submit(()->{start.await();try{return ingest(c("global-key",id,"100")).outcome().name();}catch(BusinessException e){require(e.errorCode()==ErrorCode.CONFLICT,"global identity ownership");return "CONFLICT";}}));}
            start.countDown();var outcomes=List.of(jobs.get(0).get(10,TimeUnit.SECONDS),jobs.get(1).get(10,TimeUnit.SECONDS));
            require(outcomes.contains("PROCESSED")&&outcomes.contains("CONFLICT"),"different-stock global unique race");
        }
        require(p.facts().get("quote_events")==1&&p.facts().get("market_stream_state")==1,"loser cannot update its stream");
        pass("global-event-key-race-across-two-stock-locks-is-a-payload-conflict");
    }
    QuoteEvent event(String key){var e=new QuoteEvent();e.setEventKey(key);e.setStockId(1L);e.setPrice(new BigDecimal("100"));
        e.setEventTime(MarketTime.utc(RealPipelineExperiment.EVENT_ORIGIN));e.setEventTimeNs(MarketTime.nanos(RealPipelineExperiment.EVENT_ORIGIN));
        e.setSource("ALPACA_IEX");e.setOriginType("REAL");e.setProvider("ALPACA");e.setFeed("iex");e.setReceivedAt(e.getEventTime());return e;}
    void mapperWait(boolean commit)throws Exception{
        reset();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);var contenderStarted=new CountDownLatch(1);var waited=new AtomicLong();var code=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)){
            var owner=workers.submit(()->transaction().execute(status->{p.sessions.getMapper(QuoteEventMapper.class).insert(event("pending"));inserted.countDown();
                try{require(release.await(5,TimeUnit.SECONDS),"owner released");}catch(InterruptedException e){throw new IllegalStateException(e);}if(!commit)status.setRollbackOnly();return true;}));
            require(inserted.await(5,TimeUnit.SECONDS),"uncommitted insert exists");
            var contender=workers.submit(()->transaction().execute(status->{long begin=System.nanoTime();contenderStarted.countDown();
                try{p.sessions.getMapper(QuoteEventMapper.class).insert(event("pending"));require(!commit,"owner rollback allows insert");}
                catch(DuplicateKeyException e){require(commit,"only committed winner duplicates");var sql=(SQLException)e.getMostSpecificCause();code.set(sql.getErrorCode());require(code.get()==1062&&"23000".equals(sql.getSQLState()),"real translated 1062/23000");
                    require(sql.getMessage().endsWith("for key 'quote_events.uk_quote_events_event_key'"),"verified server constraint suffix");}
                require(!status.isRollbackOnly()&&p.sessions.getMapper(QuoteEventMapper.class).findByEventKey("pending")!=null,"same transaction reads visible winner");
                waited.set(System.nanoTime()-begin);return true;}));
            require(contenderStarted.await(3,TimeUnit.SECONDS),"contender started");Thread.sleep(150);require(!contender.isDone(),"unique insert waits for uncommitted owner");release.countDown();
            require(owner.get(10,TimeUnit.SECONDS)&&contender.get(10,TimeUnit.SECONDS),"both transactions complete");
        }finally{release.countDown();}
        require(p.facts().get("quote_events")==1,"only one received row exists");
        p.checks.add(Map.of("name",commit?"mapper-unique-wait-owner-commits":"mapper-unique-wait-owner-rolls-back","pass",true,"waitMs",waited.get()/1e6,"vendorError",code.get(),"scope","low-level transaction probe; production stock lock retained"));
    }
    void uniqueLockRollbackProbe()throws Exception{
        reset();try(var owner=p.pool.getConnection();var workers=Executors.newFixedThreadPool(2)){
            owner.setAutoCommit(false);rawInsert(owner,"shared-lock");var ready=new CountDownLatch(2);List<Future<Integer>> jobs=new ArrayList<>();
            for(int i=0;i<2;i++)jobs.add(workers.submit(()->{try(var connection=p.pool.getConnection()){
                connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);ready.countDown();
                try{rawInsert(connection,"shared-lock");connection.commit();return 0;}catch(SQLException e){connection.rollback();require(e.getErrorCode()==1213||e.getErrorCode()==1062,"expected deadlock or duplicate only");return e.getErrorCode();}
            }}));
            require(ready.await(3,TimeUnit.SECONDS),"two duplicate waiters ready");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<end&&p.jdbc.queryForObject("SHOW STATUS LIKE 'Innodb_row_lock_current_waits'",(rs,n)->rs.getLong(2))<2)Thread.sleep(10);
            owner.rollback();var errors=List.of(jobs.get(0).get(10,TimeUnit.SECONDS),jobs.get(1).get(10,TimeUnit.SECONDS));
            require(Collections.frequency(errors,0)==1&&p.facts().get("quote_events")==1,"one winner, loser rolled back");
            p.checks.add(Map.of("name","innodb-duplicate-shared-lock-rollback-probe","pass",true,"outcomes",errors,"scope","direct JDBC diagnostic; no application lock removed; caller must retry a 1213 transaction"));
        }
    }
    void rawInsert(Connection connection,String key)throws SQLException{
        try(var statement=connection.prepareStatement("INSERT INTO quote_events(event_key,stock_id,price,event_time,source,origin_type) VALUES(?,1,100,'2026-10-30 14:00:00','FIXTURE','REAL')")){statement.setString(1,key);statement.executeUpdate();}
    }
}
