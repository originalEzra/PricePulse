package com.ezra.pricepulse.performance;

import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.matching.*;
import com.ezra.pricepulse.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.*;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.*;

/** Offline synthetic benchmark. No Spring application, Flyway, production configuration or feed. */
public final class RuleSelectionExperiment {
    static final String COLUMNS="id,stock_id,status,direction,threshold_price,rearm_price";
    static final String KEY=RuleIndex.key(1), TOKEN="synthetic-index-token";
    static final ObjectMapper JSON=new ObjectMapper();
    static final int[][] ORDERS={{0,1,2},{1,2,0},{2,0,1},{2,1,0},{1,0,2},{0,2,1}};
    static final long SEED=20261031L;
    final Path output;
    final boolean smoke;
    final int samples,warmup,eventCount;
    final Map<String,Object> report=new LinkedHashMap<>();
    final List<Map<String,Object>> latency=new ArrayList<>(),builds=new ArrayList<>();
    final List<Map<String,Object>> plans=new ArrayList<>();
    StringRedisTemplate redis;
    RuleIndex index;
    ThresholdIndex threshold;
    Store store;
    long rebuilds;

    public static void main(String[] args)throws Exception {new RuleSelectionExperiment(args).run();}
    RuleSelectionExperiment(String[] args)throws Exception {
        Map<String,String> options=new HashMap<>();for(String arg:args){var pair=arg.split("=",2);options.put(pair[0],pair.length==2?pair[1]:"true");}
        smoke=Boolean.parseBoolean(options.getOrDefault("--smoke","false"));
        samples=Integer.parseInt(options.getOrDefault("--samples",smoke?"8":"120"));
        warmup=Integer.parseInt(options.getOrDefault("--warmup",smoke?"3":"30"));
        eventCount=Integer.parseInt(options.getOrDefault("--events",smoke?"200":"100000"));
        output=Path.of(options.getOrDefault("--output","target/rule-selection-"+Instant.now().toString().replace(":",""))).toAbsolutePath().normalize();
        if(!output.startsWith(Path.of("target").toAbsolutePath().normalize())||Files.exists(output.resolve("summary.json")))throw new IllegalArgumentException("Use a new output under target/");
        if(samples<1||warmup<0||eventCount<100||eventCount%100!=0)throw new IllegalArgumentException("Invalid sample sizes");
        Files.createDirectories(output);
    }
    void run()throws Exception {
        long began=System.nanoTime();String password=UUID.randomUUID().toString();
        try(var mysql=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("pp_selection_bench").withUsername("root").withPassword(password)
                    .withCommand("--performance-schema=ON","--innodb-buffer-pool-size=134217728","--max-connections=30")
                    .withCreateContainerCmdModifier(c->bounded(c.getHostConfig(),3306));
            var cache=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379)
                    .withCommand("redis-server","--save","","--appendonly","no","--requirepass",password)
                    .withCreateContainerCmdModifier(c->bounded(c.getHostConfig(),6379))){
            mysql.start();cache.start();
            var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
            var config=new RedisStandaloneConfiguration(cache.getHost(),cache.getMappedPort(6379));config.setPassword(password);
            var factory=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().clientResources(resources).build());
            factory.afterPropertiesSet();factory.start();redis=new StringRedisTemplate(factory);threshold=new ThresholdIndex(redis);
            var metrics=new SimpleMeterRegistry();
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())){
                store=new Store(connection);store.schema();
                AlertRuleMapper rules=(AlertRuleMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AlertRuleMapper.class},(p,m,a)->{
                    if(!m.getName().equals("listIndexable"))throw new UnsupportedOperationException(m.getName());
                    rebuilds++;return store.all("rebuild_rules");
                });
                StockMapper stocks=(StockMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{StockMapper.class},(p,m,a)->{
                    if(!m.getName().equals("updateRuleIndexToken"))throw new UnsupportedOperationException(m.getName());
                    return store.update("TOKEN_WRITE","UPDATE bench_stocks SET token=? WHERE variant='maintenance'",a[1]);
                });
                index=new RuleIndex(threshold,rules,stocks,metrics);
                report.put("environment",Map.ofEntries(Map.entry("startedAtUtc",Instant.now().toString()),Map.entry("java",System.getProperty("java.runtime.version")),
                        Map.entry("os",System.getProperty("os.name")),Map.entry("arch",System.getProperty("os.arch")),Map.entry("visibleProcessors",Runtime.getRuntime().availableProcessors()),
                        Map.entry("maxHeapBytes",Runtime.getRuntime().maxMemory()),Map.entry("mysql",store.scalar("SELECT VERSION()")),Map.entry("redisImage","redis:7.4.6-alpine"),
                        Map.entry("eachContainerCpuLimit",1),Map.entry("eachContainerMemoryLimitBytes",768L*1024*1024),Map.entry("mysqlBufferPoolBytes",134217728),
                        Map.entry("innodbFlushLogAtTrxCommit",store.scalar("SELECT @@innodb_flush_log_at_trx_commit")),Map.entry("performanceSchema",store.scalar("SELECT @@performance_schema")),
                        Map.entry("commit",System.getenv().getOrDefault("PP_BENCH_COMMIT","unknown")),Map.entry("seed",SEED),Map.entry("syntheticOnly",true),Map.entry("smoke",smoke)));
                report.put("jdbcDriver",connection.getMetaData().getDriverVersion());
                var sourceHashes=new LinkedHashMap<String,String>();for(String file:List.of("src/main/java/com/ezra/pricepulse/matching/ThresholdIndex.java","src/main/java/com/ezra/pricepulse/matching/RuleIndex.java","src/test/java/com/ezra/pricepulse/performance/RuleSelectionExperiment.java"))sourceHashes.put(file,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file)))));report.put("sourceSha256",sourceHashes);
                try(var raw=csv("latency.csv.gz")){
                    raw.write("rules,density,move,sample,scheme,nanos,candidates,equal,ids_sha256\n");
                    for(int size:smoke?new int[]{200,500}:new int[]{1000,10000,50000})latency(size,raw);
                }
                replay();maintenance();
                report.put("latency",latency);report.put("builds",builds);report.put("plans",plans);
                report.put("elapsedSeconds",(System.nanoTime()-began)/1e9);report.put("finishedAtUtc",Instant.now().toString());
                save("summary.json",report);
                System.out.println("EXPERIMENT_COMPLETE output="+Path.of("").toAbsolutePath().relativize(output)+" seconds="+report.get("elapsedSeconds"));
            }finally{metrics.close();factory.destroy();resources.shutdown();}
        }
    }
    static void bounded(HostConfig config,int port){config.withNanoCPUs(1_000_000_000L).withMemory(768L*1024*1024)
            .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(port)));}

    void seed(int count)throws Exception {
        redis.delete(KEY);store.exec("DROP INDEX idx_bench_trigger ON bench_rules",true);store.exec("DROP INDEX idx_bench_rearm ON bench_rules",true);
        store.exec("TRUNCATE TABLE bench_rules",false);store.resetGuards();
        Random random=new Random(SEED+count);store.db.setAutoCommit(false);
        try(var statement=store.db.prepareStatement("INSERT INTO bench_rules(id,stock_id,status,direction,threshold_price,rearm_price) VALUES(?,1,?,?,?,?)")){
            for(int id=1;id<=count;id++){
                int group=(id-1)%4;boolean above=group%2==0;
                BigDecimal price=money(900000+random.nextInt(200001));
                BigDecimal rearm=price.add(money((above?-1:1)*(500+random.nextInt(7001))));
                statement.setLong(1,id);statement.setString(2,group<2?"ACTIVE":"COOLDOWN");statement.setString(3,above?"ABOVE":"BELOW");
                statement.setBigDecimal(4,price);statement.setBigDecimal(5,rearm);statement.addBatch();
                if(id%1000==0)statement.executeBatch();
            }statement.executeBatch();store.db.commit();
        }finally{store.db.setAutoCommit(true);}
        for(String kind:List.of("trigger","rearm")){
            long start=System.nanoTime();store.exec("CREATE INDEX idx_bench_"+kind+" ON bench_rules(stock_id,status,direction,"+(kind.equals("trigger")?"threshold_price":"rearm_price")+")",false);
            builds.add(Map.of("rules",count,"kind","MYSQL_INDEX_"+kind,"nanos",System.nanoTime()-start));
        }
        store.exec("ANALYZE TABLE bench_rules",false);
        for(int i=0;i<3;i++){
            redis.delete(KEY);long before=rebuilds,start=System.nanoTime(),sql=store.count("RULE");
            index.candidates(stock(TOKEN),money(1000000),money(1000100));
            builds.add(Map.of("rules",count,"kind","REDIS_COLD_WITH_DB_LOAD","sample",i,"nanos",System.nanoTime()-start,"rebuilds",rebuilds-before,"ruleSelects",store.count("RULE")-sql));
        }
        Long bytes=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("return redis.call('MEMORY','USAGE',KEYS[1]) or 0",Long.class),List.of(KEY));
        builds.add(Map.of("rules",count,"kind","STORAGE","redisKeyBytes",bytes==null?0:bytes,
                "mysql",store.rows("SELECT DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='bench_rules'")));
        try(var data=csv("rules-"+count+".csv.gz")){
            data.write("id,stock_id,status,direction,threshold_price,rearm_price\n");
            for(var r:store.all("dataset_export_rules"))data.write(r.getId()+",1,"+r.getStatus()+","+r.getDirection()+","+r.getThresholdPrice()+","+r.getRearmPrice()+"\n");
        }
    }
    void latency(int size,BufferedWriter raw)throws Exception {
        seed(size);System.out.println("LATENCY rules="+size);
        for(var density:Map.of("sparse",100,"medium",10000,"dense",80000).entrySet())for(boolean up:new boolean[]{true,false}){
            BigDecimal previous=money(1000000),current=money(1000000+(up?1:-1)*density.getValue());
            String scenario=density.getKey()+"-"+(up?"UP":"DOWN");
            var plan=new LinkedHashMap<String,Object>();plan.put("rules",size);plan.put("scenario",scenario);
            plan.put("idPlan",store.plan(previous,current,false));plan.put("hydrationPlan",store.plan(previous,current,true));plans.add(plan);
            List<List<Long>> timings=List.of(new ArrayList<>(),new ArrayList<>(),new ArrayList<>());List<Integer> counts=new ArrayList<>();
            for(int sample=-warmup;sample<samples;sample++){
                @SuppressWarnings("unchecked") List<Long>[] answers=new List[3];long[] nanos=new long[3];
                for(int scheme:ORDERS[Math.floorMod(sample,6)]){
                    long start=System.nanoTime();answers[scheme]=switch(scheme){
                        case 0->store.all("scan_rules").stream().filter(r->cross(r,previous,current)).map(AlertRule::getId).toList();
                        case 1->store.rangeIds(previous,current,"latency_rules");
                        default->index.candidates(stock(TOKEN),previous,current);
                    };nanos[scheme]=System.nanoTime()-start;
                }
                String hash=equal(answers);if(sample<0)continue;counts.add(answers[0].size());
                for(int scheme=0;scheme<3;scheme++){
                    timings.get(scheme).add(nanos[scheme]);raw.write(size+","+density.getKey()+","+(up?"UP":"DOWN")+","+sample+","+scheme+","+nanos[scheme]+","+answers[scheme].size()+",true,"+hash+"\n");
                }
            }
            var row=new LinkedHashMap<String,Object>();row.put("rules",size);row.put("scenario",scenario);row.put("previous",previous.toPlainString());row.put("current",current.toPlainString());
            row.put("samples",samples);row.put("warmup",warmup);row.put("averageCandidates",counts.stream().mapToInt(Integer::intValue).average().orElse(0));row.put("mismatches",0);
            var groups=new TreeMap<String,Long>();for(var r:store.all("distribution_rules"))if(cross(r,previous,current))groups.merge(r.getStatus()+"_"+r.getDirection(),1L,Long::sum);row.put("candidateGroups",groups);
            for(int i=0;i<3;i++)row.put(List.of("MYSQL_FULL_JAVA","MYSQL_BTREE_IDS","PRODUCTION_REDIS_IDS").get(i),percentiles(timings.get(i)));
            latency.add(row);raw.flush();save("latency-checkpoint.json",latency);
            System.out.println("LATENCY_DONE rules="+size+" scenario="+scenario+" candidates="+row.get("averageCandidates"));
        }
    }

    record Event(int number,String category,BigDecimal previous,BigDecimal current){}
    List<Event> events(){
        var types=new ArrayList<String>();for(int i=0;i<eventCount;i++)types.add(i<eventCount/5?"UNCHANGED":i<eventCount*4/5?"SMALL":i<eventCount*95/100?"NORMAL":"JUMP");
        Collections.shuffle(types,new Random(SEED));Random random=new Random(SEED+1);var result=new ArrayList<Event>();long price=1000000;
        for(int i=0;i<eventCount;i++){
            String category=types.get(i);int cents=switch(category){case "UNCHANGED"->0;case "SMALL"->1+random.nextInt(5);case "NORMAL"->6+random.nextInt(15);default->21+random.nextInt(80);};
            long next=price+(random.nextBoolean()?1:-1)*cents*100L;
            if(next<950000||next>1050000)next=price-(next-price);
            result.add(new Event(i,category,money(price),money(next)));price=next;
        }return result;
    }
    void replay()throws Exception {
        int size=smoke?500:10000;seed(size);var events=events();
        try(var data=csv("events.csv.gz")){data.write("event,category,previous,current\n");for(var e:events)data.write(e.number+","+e.category+","+e.previous+","+e.current+"\n");}
        // Warm all prepared paths, then reset only experimental guard rows. Rule states are a fixed snapshot.
        for(var e:events.subList(0,Math.min(300,events.size())))for(int scheme:ORDERS[e.number%6])selectReplay(e,scheme,false);
        store.resetGuards();store.meter.clear();long rebuildBefore=rebuilds;var dbBefore=store.digest();
        var totals=new LinkedHashMap<String,ReplayTotals>();for(String name:List.of("MYSQL_DIRECT","REDIS_PREFILTER","MYSQL_EQUAL_PRICE_FASTPATH"))totals.put(name,new ReplayTotals());
        Instant began=Instant.now();long start=System.nanoTime();store.windowStart=start;
        try(var raw=csv("replay.csv.gz")){
            raw.write("event,category,scheme,global_elapsed_ns,selector_ns,guarded_total_ns,candidates,rule_selects,rule_query_ns,returned_rule_rows,ids_sha256\n");
            for(var event:events){
                @SuppressWarnings("unchecked") List<Long>[] answers=new List[3];
                for(int scheme:ORDERS[event.number%6]){
                    String name=List.of("MYSQL_DIRECT","REDIS_PREFILTER","MYSQL_EQUAL_PRICE_FASTPATH").get(scheme);store.variant=name;store.windowScheme=name;
                    var tally=totals.get(name);long ruleCount=store.count("RULE"),ruleNs=store.nanos("RULE"),rows=store.returned("RULE"),totalStart=System.nanoTime();
                    store.db.setAutoCommit(false);
                    try{
                        Stock guard=store.guard(name,event.previous,event.number);
                        long selectStart=System.nanoTime();answers[scheme]=selectReplay(event,scheme,true,guard);long selected=System.nanoTime()-selectStart;
                        store.advance(name,event.current,event.number+1);store.db.commit();long guarded=System.nanoTime()-totalStart;
                        tally.selectorNs+=selected;tally.guardedNs+=guarded;tally.events++;if(!answers[scheme].isEmpty())tally.nonempty++;
                        tally.candidates+=answers[scheme].size();
                        raw.write(event.number+","+event.category+","+name+","+(System.nanoTime()-start)+","+selected+","+guarded+","+answers[scheme].size()+","+(store.count("RULE")-ruleCount)+","+(store.nanos("RULE")-ruleNs)+","+(store.returned("RULE")-rows)+","+hash(answers[scheme])+"\n");
                    }catch(Throwable error){store.db.rollback();throw error;}finally{store.db.setAutoCommit(true);}
                }
                equal(answers);
                if((event.number+1)%10000==0){raw.flush();System.out.println("REPLAY events="+(event.number+1)+"/"+eventCount);}
            }
        }
        long elapsed=System.nanoTime()-start;store.windowScheme=null;var databaseDelta=Store.delta(dbBefore,store.digest());
        var rows=new ArrayList<Map<String,Object>>();
        for(var entry:totals.entrySet()){
            var r=new LinkedHashMap<String,Object>();String name=entry.getKey();ReplayTotals t=entry.getValue();
            r.put("scheme",name);r.put("events",t.events);r.put("nonemptyEvents",t.nonempty);r.put("candidateRows",t.candidates);r.put("nonemptyRatio",t.nonempty/(double)t.events);
            r.put("selectorTotalNs",t.selectorNs);r.put("guardedTotalNs",t.guardedNs);r.put("sql",JSON.convertValue(store.meter.get(name),Map.class));
            long client=store.count(name,"RULE");r.put("ruleSelects",client);r.put("ruleSelectsPerCommonWindowSecond",client/(elapsed/1e9));
            String alias=switch(name){case "MYSQL_DIRECT"->"direct_rules";case "REDIS_PREFILTER"->"prefiltered_rules";default->"fast_rules";};
            long server=databaseDelta.stream().filter(d->d.get("sql").toString().contains("`"+alias+"`")).mapToLong(d->((Number)d.get("count")).longValue()).sum();
            r.put("databaseVerifiedRuleSelects",server);if(server!=client)throw new AssertionError("Driver/db count mismatch: "+name+" "+client+"/"+server);
            rows.add(r);
        }
        var replay=new LinkedHashMap<String,Object>();replay.put("rules",size);replay.put("events",eventCount);replay.put("seed",SEED);replay.put("categories",Map.of("UNCHANGED",eventCount/5,"SMALL",eventCount*3/5,"NORMAL",eventCount*15/100,"JUMP",eventCount/20));
        replay.put("windowStartUtc",began.toString());replay.put("windowEndUtc",Instant.now().toString());replay.put("commonWindowSeconds",elapsed/1e9);replay.put("schemes",rows);
        replay.put("redisQueries",eventCount);replay.put("redisRebuilds",rebuilds-rebuildBefore);replay.put("mismatches",0);replay.put("databaseDigestDeltas",databaseDelta);
        replay.put("sqlTenSecondBins",store.bins);replay.put("fixedRuleStates",true);replay.put("guardSelectorsPerEvent",2);replay.put("guardUpdatesPerEvent",2);replay.put("fullFormalPipeline",false);
        report.put("replay",replay);save("replay-summary.json",replay);
    }
    static final class ReplayTotals{long events,nonempty,candidates,selectorNs,guardedNs;}
    List<Long> selectReplay(Event e,int scheme,boolean measured)throws Exception{return selectReplay(e,scheme,measured,stock(TOKEN));}
    List<Long> selectReplay(Event e,int scheme,boolean measured,Stock guard)throws Exception{
        if(scheme==2&&e.previous.compareTo(e.current)==0)return List.of();
        if(scheme!=1)return store.rangeRows(e.previous,e.current,scheme==0?"direct_rules":"fast_rules").stream().map(AlertRule::getId).toList();
        var ids=index.candidates(guard,e.previous,e.current);var loaded=new ArrayList<Long>();
        for(int offset=0;offset<ids.size();offset+=500)loaded.addAll(store.byIds(ids.subList(offset,Math.min(offset+500,ids.size()))).stream().map(AlertRule::getId).toList());
        return loaded;
    }

    void maintenance()throws Exception {
        seed(smoke?500:10000);store.variant="MAINTENANCE";store.windowScheme=null;store.meter.clear();store.resetGuards();
        var steps=new ArrayList<Map<String,Object>>();BigDecimal previous=money(1000000),current=money(1001000);
        for(String action:List.of("ADD","PAUSE","DELETE","CACHE_MISSING","ROLLBACK_TOKEN_MISMATCH","WARM_RECOVERY")){
            long before=rebuilds,sqlBefore=store.count("RULE"),begin=System.nanoTime();Stock old=store.maintenanceStock();
            if(action.equals("CACHE_MISSING"))redis.delete(KEY);
            else if(!action.equals("WARM_RECOVERY")){
                store.db.setAutoCommit(false);
                try{
                    AlertRule changed;
                    if(action.equals("ADD")){
                        store.update("MUTATION_RULE_WRITE","INSERT INTO bench_rules VALUES(999999,1,'ACTIVE','ABOVE',100.0500,99.0000)");changed=store.find(999999);
                    }else if(action.equals("ROLLBACK_TOKEN_MISMATCH")){
                        store.update("MUTATION_RULE_WRITE","UPDATE bench_rules SET threshold_price=threshold_price+0.1 WHERE id=1");changed=store.find(1);
                    }else{
                        int id=action.equals("PAUSE")?2:3;
                        store.update("MUTATION_RULE_WRITE","UPDATE bench_rules SET status=? WHERE id=?",action.equals("PAUSE")?"PAUSED":"DELETED",id);changed=store.find(id);
                    }
                    index.changed(old,List.of(changed));
                    if(action.equals("ROLLBACK_TOKEN_MISMATCH"))store.db.rollback();else store.db.commit();
                }finally{store.db.setAutoCommit(true);}
            }
            Stock now=store.maintenanceStock();var actual=index.candidates(now,previous,current);var expected=store.rangeIds(previous,current,"verification_rules");
            equal(List.of(actual,expected));
            steps.add(Map.of("action",action,"elapsedNs",System.nanoTime()-begin,"rebuilds",rebuilds-before,"allRuleSelectsIncludingVerification",store.count("RULE")-sqlBefore,"equal",true));
        }
        report.put("maintenance",Map.of("steps",steps,"sql",store.meter,"note","Actual RuleIndex.changed and ThresholdIndex MISS/rebuild; only experimental tables/Redis"));
        save("maintenance.json",report.get("maintenance"));
    }

    static final class SqlStats{public long executions,rows,nanos;void add(long returned,long elapsed){executions++;rows+=returned;nanos+=elapsed;}}
    final class Store implements AutoCloseable{
        final Connection db;final Map<String,PreparedStatement> prepared=new HashMap<>();
        final Map<String,Map<String,SqlStats>> meter=new LinkedHashMap<>();final Map<String,Map<Long,Long>> bins=new LinkedHashMap<>();
        String variant="SETUP",windowScheme;long windowStart;
        Store(Connection db){this.db=db;}
        void schema()throws SQLException{
            exec("CREATE TABLE bench_rules(id BIGINT PRIMARY KEY,stock_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,direction VARCHAR(8) NOT NULL,threshold_price DECIMAL(19,4) NOT NULL,rearm_price DECIMAL(19,4) NOT NULL) ENGINE=InnoDB",false);
            exec("CREATE TABLE bench_stocks(variant VARCHAR(40) PRIMARY KEY,stock_id BIGINT NOT NULL,token VARCHAR(64) NOT NULL,price DECIMAL(19,4) NOT NULL) ENGINE=InnoDB",false);
            exec("CREATE TABLE bench_watermark(variant VARCHAR(40) PRIMARY KEY,accepted_sequence BIGINT NOT NULL) ENGINE=InnoDB",false);resetGuards();
        }
        void resetGuards()throws SQLException{
            for(String name:List.of("MYSQL_DIRECT","REDIS_PREFILTER","MYSQL_EQUAL_PRICE_FASTPATH","maintenance")){
                update("SETUP","INSERT INTO bench_stocks VALUES(?,1,?,100.0000) ON DUPLICATE KEY UPDATE token=?,price=100.0000",name,TOKEN,TOKEN);
                update("SETUP","INSERT INTO bench_watermark VALUES(?,0) ON DUPLICATE KEY UPDATE accepted_sequence=0",name);
            }
        }
        Stock guard(String name,BigDecimal previous,long sequence)throws SQLException{
            long start=System.nanoTime();String token;BigDecimal price;
            try(var rs=bind("SELECT token,price FROM bench_stocks WHERE variant=? FOR UPDATE",name).executeQuery()){if(!rs.next())throw new AssertionError("Missing stock guard");token=rs.getString(1);price=rs.getBigDecimal(2);}
            record("STOCK_LOCK_SELECT",1,System.nanoTime()-start);start=System.nanoTime();
            try(var rs=bind("SELECT accepted_sequence FROM bench_watermark WHERE variant=?",name).executeQuery()){if(!rs.next()||rs.getLong(1)!=sequence||price.compareTo(previous)!=0)throw new AssertionError("Persisted replay precondition mismatch");}
            record("WATERMARK_SELECT",1,System.nanoTime()-start);return stock(token);
        }
        void advance(String name,BigDecimal price,long seq)throws SQLException{update("STOCK_UPDATE","UPDATE bench_stocks SET price=? WHERE variant=?",price,name);update("WATERMARK_UPDATE","UPDATE bench_watermark SET accepted_sequence=? WHERE variant=?",seq,name);}
        Stock maintenanceStock()throws SQLException{long start=System.nanoTime();try(var rs=bind("SELECT token FROM bench_stocks WHERE variant='maintenance'").executeQuery()){rs.next();Stock value=stock(rs.getString(1));record("MAINTENANCE_TOKEN_SELECT",1,System.nanoTime()-start);return value;}}
        List<AlertRule> all(String alias)throws SQLException{return queryRules("SELECT "+COLUMNS+" FROM bench_rules AS "+alias+" WHERE stock_id=1 AND status IN ('ACTIVE','COOLDOWN')","RULE_REBUILD_OR_SCAN");}
        AlertRule find(long id)throws SQLException{return queryRules("SELECT "+COLUMNS+" FROM bench_rules AS changed_rules WHERE id=?","RULE_MAINTENANCE_READ",id).getFirst();}
        List<AlertRule> byIds(List<Long> ids)throws SQLException{return queryRules("SELECT "+COLUMNS+" FROM bench_rules AS prefiltered_rules WHERE stock_id=1 AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")","RULE_HYDRATE",ids.toArray());}
        String range(BigDecimal previous,BigDecimal current,String alias,boolean full){
            boolean up=current.compareTo(previous)>0;String cols=full?COLUMNS:"id",lo=up?">":">=",hi=up?"<=":"<";
            return "SELECT "+cols+" FROM bench_rules AS "+alias+" WHERE stock_id=1 AND status='ACTIVE' AND direction='"+(up?"ABOVE":"BELOW")+"' AND threshold_price"+lo+"? AND threshold_price"+hi+"? UNION ALL SELECT "+cols+" FROM bench_rules AS "+alias+" WHERE stock_id=1 AND status='COOLDOWN' AND direction='"+(up?"BELOW":"ABOVE")+"' AND rearm_price"+lo+"? AND rearm_price"+hi+"?";
        }
        Object[] bounds(BigDecimal a,BigDecimal b){var low=a.min(b);var high=a.max(b);return new Object[]{low,high,low,high};}
        List<Long> rangeIds(BigDecimal a,BigDecimal b,String alias)throws SQLException{
            long start=System.nanoTime();var ids=new ArrayList<Long>();
            try(var rs=bind(range(a,b,alias,false),bounds(a,b)).executeQuery()){while(rs.next())ids.add(rs.getLong(1));}
            record("RULE_RANGE_IDS",ids.size(),System.nanoTime()-start);return ids;
        }
        List<AlertRule> rangeRows(BigDecimal a,BigDecimal b,String alias)throws SQLException{return queryRules(range(a,b,alias,true),"RULE_RANGE_HYDRATE",bounds(a,b));}
        List<AlertRule> queryRules(String sql,String kind,Object...params)throws SQLException{
            long start=System.nanoTime();var result=new ArrayList<AlertRule>();try(var rs=bind(sql,params).executeQuery()){while(rs.next()){
                AlertRule r=new AlertRule();r.setId(rs.getLong(1));r.setStockId(rs.getLong(2));r.setStatus(AlertRuleStatus.valueOf(rs.getString(3)));r.setDirection(AlertDirection.valueOf(rs.getString(4)));
                r.setThresholdPrice(rs.getBigDecimal(5));r.setRearmPrice(rs.getBigDecimal(6));r.setTriggerMode(AlertTriggerMode.REPEAT);result.add(r);
            }}record(kind,result.size(),System.nanoTime()-start);return result;
        }
        int update(String kind,String sql,Object...args)throws SQLException{long start=System.nanoTime();int n=bind(sql,args).executeUpdate();record(kind,n,System.nanoTime()-start);return n;}
        void record(String kind,long rows,long ns){meter.computeIfAbsent(variant,k->new LinkedHashMap<>()).computeIfAbsent(kind,k->new SqlStats()).add(rows,ns);
            if(windowScheme!=null&&kind.startsWith("RULE"))bins.computeIfAbsent(windowScheme,k->new TreeMap<>()).merge((System.nanoTime()-windowStart)/10_000_000_000L,1L,Long::sum);}
        long count(String prefix){return meter.values().stream().mapToLong(m->m.entrySet().stream().filter(e->e.getKey().startsWith(prefix)).mapToLong(e->e.getValue().executions).sum()).sum();}
        long count(String name,String prefix){return meter.getOrDefault(name,Map.of()).entrySet().stream().filter(e->e.getKey().startsWith(prefix)).mapToLong(e->e.getValue().executions).sum();}
        long nanos(String prefix){return meter.values().stream().flatMap(m->m.entrySet().stream()).filter(e->e.getKey().startsWith(prefix)).mapToLong(e->e.getValue().nanos).sum();}
        long returned(String prefix){return meter.values().stream().flatMap(m->m.entrySet().stream()).filter(e->e.getKey().startsWith(prefix)).mapToLong(e->e.getValue().rows).sum();}
        PreparedStatement bind(String sql,Object...args)throws SQLException{var ps=prepared.get(sql);if(ps==null){ps=db.prepareStatement(sql);prepared.put(sql,ps);}for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;}
        void exec(String sql,boolean ignore)throws SQLException{try(var s=db.createStatement()){s.execute(sql);}catch(SQLException e){if(!ignore||e.getErrorCode()!=1091)throw e;}}
        String scalar(String sql)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
        List<Map<String,Object>> rows(String sql)throws SQLException{try(var s=db.createStatement();var r=s.executeQuery(sql)){var result=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));result.add(row);}return result;}}
        String plan(BigDecimal a,BigDecimal b,boolean full)throws SQLException{try(var rs=bind("EXPLAIN ANALYZE "+range(a,b,"explain_rules",full),bounds(a,b)).executeQuery()){rs.next();return rs.getString(1);}}
        List<Map<String,Object>> digest()throws SQLException{return rows("SELECT DIGEST_TEXT AS `sql`,COUNT_STAR AS `count`,SUM_ROWS_SENT AS returnedRows,SUM_ROWS_EXAMINED AS examinedRows,SUM_TIMER_WAIT AS serverPicoseconds FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() AND DIGEST_TEXT LIKE 'SELECT%' AND DIGEST_TEXT LIKE '%`bench_rules`%'");}
        static List<Map<String,Object>> delta(List<Map<String,Object>> before,List<Map<String,Object>> after){var old=new HashMap<String,Map<String,Object>>();before.forEach(r->old.put(r.get("sql").toString(),r));var result=new ArrayList<Map<String,Object>>();for(var row:after){var r=new LinkedHashMap<String,Object>();String sql=row.get("sql").toString();r.put("sql",sql);for(String field:List.of("count","returnedRows","examinedRows","serverPicoseconds"))r.put(field,((Number)row.get(field)).longValue()-((Number)old.getOrDefault(sql,Map.of()).getOrDefault(field,0)).longValue());if(((Number)r.get("count")).longValue()>0)result.add(r);}return result;}
        public void close()throws SQLException{for(var s:prepared.values())s.close();db.close();}
    }
    static boolean cross(AlertRule r,BigDecimal previous,BigDecimal current){
        int sign=current.compareTo(previous);if(sign==0)return false;
        boolean active=r.getStatus()==AlertRuleStatus.ACTIVE,cool=r.getStatus()==AlertRuleStatus.COOLDOWN,above=r.getDirection()==AlertDirection.ABOVE;
        BigDecimal value=active?r.getThresholdPrice():r.getRearmPrice();
        if(sign>0)return ((active&&above)||(cool&&!above))&&value.compareTo(previous)>0&&value.compareTo(current)<=0;
        return ((active&&!above)||(cool&&above))&&value.compareTo(current)>=0&&value.compareTo(previous)<0;
    }
    static Stock stock(String token){return new Stock(1,"SYNTHETIC","Synthetic benchmark","US","USD",money(1000000),java.time.LocalDateTime.of(2026,10,30,13,30),"ACTIVE",null,null,token);}
    static BigDecimal money(long ticks){return BigDecimal.valueOf(ticks,4);}
    static String equal(List<Long>[] answers)throws Exception{return equal(Arrays.asList(answers));}
    static String equal(List<List<Long>> answers)throws Exception{var expected=new TreeSet<>(answers.getFirst());for(var answer:answers)if(answer.size()!=new HashSet<>(answer).size()||!expected.equals(new TreeSet<>(answer)))throw new AssertionError("Candidate ID sets differ");return hash(answers.getFirst());}
    static String hash(List<Long> ids)throws Exception{var digest=MessageDigest.getInstance("SHA-256");for(long id:new TreeSet<>(ids))digest.update((id+",").getBytes(java.nio.charset.StandardCharsets.US_ASCII));return HexFormat.of().formatHex(digest.digest());}
    static Map<String,Object> percentiles(List<Long> samples){long[] values=samples.stream().mapToLong(Long::longValue).sorted().toArray();return Map.of("p50Ns",values[(int)Math.ceil(values.length*.5)-1],"p95Ns",values[(int)Math.ceil(values.length*.95)-1],"p99Ns",values[(int)Math.ceil(values.length*.99)-1],"meanNs",Arrays.stream(values).average().orElse(0));}
    BufferedWriter csv(String name)throws IOException{return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(output.resolve(name))),java.nio.charset.StandardCharsets.UTF_8));}
    void save(String name,Object value)throws IOException{JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(name).toFile(),value);}
}
