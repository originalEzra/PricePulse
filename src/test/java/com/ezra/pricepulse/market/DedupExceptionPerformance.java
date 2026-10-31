package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.error.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Direct ingestion proxy measurements, explicitly separate from queue-entry throughput. */
final class DedupExceptionPerformance {
    final RealPipelineExperiment p;
    DedupExceptionPerformance(RealPipelineExperiment p){this.p=p;}
    void run()throws Exception{
        var paths=new ArrayList<Map<String,Object>>();
        paths.add(sequential(false));paths.add(sequential(true));paths.add(concurrent());
        p.save("direct-paths.json",Map.of("entry","direct transactional ingestion proxy; no MarketQuoteLane or input rate claim",
                "paths",paths,"callerRetries",0));
    }
    QuoteCandidate input(String key,String price){return new QuoteCandidate(key,1,new BigDecimal(price),MarketTime.utc(RealPipelineExperiment.EVENT_ORIGIN),
            "ALPACA_IEX",null,p.clock.instant(),"ALPACA","iex","direct:2026-10-30","STARTUP");}
    void prepare()throws Exception{p.clock.reset();p.seed(RealPipelineExperiment.Scenario.ZERO_RULES,1);p.ingestion.ingest(input("direct-base","100"),QuoteIngestionPolicy.LIVE);}
    PipelineProbe.Sample call(String tag,int number,QuoteCandidate input){
        long start=System.nanoTime();var sample=new PipelineProbe.Sample(tag+number,0,number,true,start,start);sample.trade=new AlpacaProtocol.Trade("PPTEST0",input.eventKey(),input.price(),input.eventTime().toInstant(java.time.ZoneOffset.UTC),input.receivedAt());
        sample.startedNanos=start;PipelineProbe.CURRENT.set(sample);
        try{
            var result=p.ingestion.ingest(input,QuoteIngestionPolicy.LIVE);sample.outcome=result.outcome().name();
            if(result.matching()!=null){sample.candidates=result.matching().candidates();sample.triggers=result.matching().triggered();sample.rearms=result.matching().rearmed();}
        }catch(BusinessException e){if(e.errorCode()!=ErrorCode.CONFLICT)throw e;sample.outcome="CONFLICT";}
        finally{sample.completedNanos=System.nanoTime();sample.finished=true;sample.record("quote_process_total",sample.completedNanos-start);PipelineProbe.CURRENT.remove();}
        return sample;
    }
    Map<String,Object> sequential(boolean conflict)throws Exception{
        prepare();String name=conflict?"payload-conflict":"existing-duplicate";var input=input("direct-base",conflict?"101":"100");
        for(int i=0;i<50;i++)call("warm-",i,input);
        var before=p.facts();var samples=new ArrayList<PipelineProbe.Sample>();long begin=System.nanoTime();
        for(int i=0;i<200;i++){var sample=call(name,i,input);require(sample.outcome.equals(conflict?"CONFLICT":"DUPLICATE"),"direct outcome");samples.add(sample);}
        long end=System.nanoTime();require(p.facts().equals(before),"duplicate/conflict has no persisted side effects");
        return result(name,samples,begin,end,before);
    }
    Map<String,Object> concurrent()throws Exception{
        prepare();var samples=new ArrayList<PipelineProbe.Sample>();
        try(var workers=Executors.newFixedThreadPool(2)){
            for(int i=0;i<20;i++)race(workers,"warm-race-"+i,i*2);
            var before=p.facts();long begin=System.nanoTime();
            for(int i=0;i<100;i++)samples.addAll(race(workers,"race-"+i,i*2));
            long end=System.nanoTime();require(p.facts().get("quote_events")-before.get("quote_events")==100,"100 unique concurrent events");
            return result("concurrent-identical-pairs",samples,begin,end,before);
        }
    }
    List<PipelineProbe.Sample> race(ExecutorService workers,String key,int number)throws Exception{
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var input=input(key,"100");
        var a=workers.submit(()->{ready.countDown();start.await();return call("race-a-",number,input);});
        var b=workers.submit(()->{ready.countDown();start.await();return call("race-b-",number+1,input);});
        require(ready.await(3,TimeUnit.SECONDS),"two concurrent callers ready");start.countDown();
        var samples=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        require(samples.stream().filter(s->s.outcome.equals("PROCESSED")).count()==1&&samples.stream().filter(s->s.outcome.equals("DUPLICATE")).count()==1,"one processed and one duplicate");return samples;
    }
    Map<String,Object> result(String name,List<PipelineProbe.Sample> samples,long begin,long end,Map<String,Long> before)throws Exception{
        var result=new LinkedHashMap<String,Object>();result.put("name",name);result.put("attempts",samples.size());result.put("elapsedSeconds",(end-begin)/1e9);
        result.put("completedAttemptsPerSecondIncludingCallerCoordination",samples.size()/((end-begin)/1e9));
        var outcomes=new TreeMap<String,Long>();var sql=new TreeMap<String,Long>();var errors=new TreeMap<String,Long>();
        for(var sample:samples){outcomes.merge(sample.outcome,1L,Long::sum);sample.sqlCounts.forEach((k,v)->sql.merge(k,v,Long::sum));sample.sqlErrors.forEach((k,v)->errors.merge(k,v,Long::sum));}
        result.put("outcomes",outcomes);result.put("jdbcExecutions",sql);result.put("sqlErrors",errors);result.put("callerRetries",0);
        var phases=new LinkedHashMap<String,Object>();for(String phase:PipelineProbe.PHASES)phases.put(phase+"_ms",PipelineProbe.distribution(samples.stream().filter(s->s.phases.containsKey(phase)).map(s->s.phases.get(phase)).toList()));
        result.put("phases",phases);var after=p.facts();var delta=new TreeMap<String,Long>();after.forEach((k,v)->delta.put(k,v-before.get(k)));result.put("tableRowDelta",delta);
        require(delta.get("alert_triggers")==0&&delta.get("outbox_events")==0&&delta.get("notifications")==0&&delta.get("simulation_runs")==0,"direct path isolation");
        var facts=new LinkedHashMap<String,Object>();facts.put("quotes",p.jdbc.queryForList("SELECT event_key,stock_id,price,event_time_ns,processing_status,origin_type FROM quote_events ORDER BY event_key"));
        facts.put("stock",p.jdbc.queryForList("SELECT id,latest_price,latest_quote_at FROM stocks ORDER BY id"));
        facts.put("stream",p.jdbc.queryForList("SELECT stock_id,event_time_ns,accepted_sequence,continuity_id FROM market_stream_state ORDER BY stock_id"));
        result.put("normalizedFinalFactsSha256",RealPipelineExperiment.sha(p.json.writeValueAsBytes(facts)));
        Path raw=p.output.resolve("direct-"+name+".csv.gz");
        try(var writer=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(raw)),StandardCharsets.UTF_8))){
            writer.write("number,event_key,outcome,business_sql_attempts,event_selects,event_inserts,duplicate_key_exceptions");for(String phase:PipelineProbe.PHASES)writer.write(","+phase+"_nanos");writer.newLine();
            for(var sample:samples){writer.write(sample.number+","+sample.trade.eventKey()+","+sample.outcome+","+sample.sqlCounts.values().stream().mapToLong(Long::longValue).sum()+","+sample.sqlCounts.getOrDefault("select:quote_events",0L)+","+sample.sqlCounts.getOrDefault("insert:quote_events",0L)+","+sample.sqlErrors.getOrDefault("insert:quote_events|1062|23000",0L));for(String phase:PipelineProbe.PHASES)writer.write(","+sample.phases.getOrDefault(phase,0L));writer.newLine();}
        }
        result.put("raw",Map.of("name",raw.getFileName().toString(),"bytes",Files.size(raw),"sha256",RealPipelineExperiment.sha(Files.readAllBytes(raw))));
        return result;
    }
    void require(boolean condition,String text){if(!condition)throw new IllegalStateException(text);}
}
