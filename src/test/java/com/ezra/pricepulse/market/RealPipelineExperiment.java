package com.ezra.pricepulse.market;

import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.matching.*;
import com.ezra.pricepulse.notification.*;
import com.ezra.pricepulse.outbox.*;
import com.ezra.pricepulse.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPOutputStream;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.*;

/** Standalone test-classpath driver. Cannot accept a URL, credential, production profile or provider address. */
public final class RealPipelineExperiment {
    static final long SEED=2026103101L;
    static final Instant EVENT_ORIGIN=Instant.parse("2026-10-30T14:00:00Z");
    static final int CAPACITY=1024;
    enum Scenario { ZERO_RULES, ZERO_HIT, FEW_HIT, BURST_100, BURST_500, BURST_1000 }
    record Case(String id,Scenario scenario,int stocks,int rate,int seconds,int repeat) { }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Path output; final String suite,path; final int seconds,warmup,repeats,rules,hitRules;final long seed;
    final int[] rates,stockCounts;final Set<Scenario> selectedScenarios;
    final List<Map<String,Object>> results=new ArrayList<>(); final List<Map<String,Object>> checks=new ArrayList<>();
    HikariDataSource pool; JdbcTemplate jdbc; StringRedisTemplate redis; RuleIndex index; QuoteIngestionService ingestion;
    StockMapper stocks; AlertRuleMapper ruleMapper; NotificationService notifications; DataSourceTransactionManager tx;
    SqlSessionTemplate sessions;
    OutboxWriter outboxWriter;
    final SimpleMeterRegistry meters=new SimpleMeterRegistry(); final EventClock clock=new EventClock();
    String mysqlUrl,password; int caseNumber;
    public static void main(String[] args)throws Exception {new RealPipelineExperiment(args).run();}
    RealPipelineExperiment(String[] args)throws Exception {
        Map<String,String> options=new LinkedHashMap<>();
        for(String arg:args){if(!arg.matches("--[a-z-]+=[a-zA-Z0-9_.,/:-]+"))throw new IllegalArgumentException("Use --name=value");String[] p=arg.substring(2).split("=",2);options.put(p[0],p[1]);}
        for(String k:options.keySet())if(!Set.of("output","suite","seconds","warmup-seconds","repeats","rules","rates","stocks","seed","hit-rules","path","scenarios","instrumentation","capture-candidates").contains(k))throw new IllegalArgumentException("Unknown option "+k);
        output=Path.of(options.getOrDefault("output","target/real-pipeline-"+System.currentTimeMillis())).toAbsolutePath().normalize();
        if(!output.startsWith(Path.of("target").toAbsolutePath().normalize())||Files.exists(output))throw new IllegalArgumentException("A new output directory under target/ is required");
        suite=options.getOrDefault("suite","smoke");seconds=Integer.parseInt(options.getOrDefault("seconds",suite.equals("smoke")?"2":"15"));
        warmup=Integer.parseInt(options.getOrDefault("warmup-seconds",suite.equals("smoke")?"1":"3"));
        repeats=Integer.parseInt(options.getOrDefault("repeats",suite.equals("smoke")?"1":"2"));rules=Integer.parseInt(options.getOrDefault("rules","1000"));
        seed=Long.parseLong(options.getOrDefault("seed",Long.toString(SEED)));hitRules=Integer.parseInt(options.getOrDefault("hit-rules","2"));path=options.getOrDefault("path","mixed");
        rates=Arrays.stream(options.getOrDefault("rates",suite.equals("sustain")?"50,100,200":"50,100,200,500,1000").split(",")).mapToInt(Integer::parseInt).toArray();
        stockCounts=Arrays.stream(options.getOrDefault("stocks","1,5,15").split(",")).mapToInt(Integer::parseInt).toArray();
        selectedScenarios=new LinkedHashSet<>();for(String s:options.getOrDefault("scenarios",suite.equals("sustain")?"ZERO_HIT,FEW_HIT":"ZERO_RULES,ZERO_HIT,FEW_HIT,BURST_100,BURST_500,BURST_1000").split(","))selectedScenarios.add(Scenario.valueOf(s));
        String instrumentation=options.getOrDefault("instrumentation","full");if(!Set.of("full","boundary").contains(instrumentation))throw new IllegalArgumentException("Unknown instrumentation");PipelineProbe.detailed=instrumentation.equals("full");
        PipelineProbe.captureCandidateIds=Boolean.parseBoolean(options.getOrDefault("capture-candidates","false"));
        if(PipelineProbe.captureCandidateIds&&!PipelineProbe.detailed)throw new IllegalArgumentException("Candidate identity collection needs full instrumentation");
        if(!Set.of("smoke","baseline","sustain","correctness").contains(suite)||seconds<1||seconds>180||warmup<1||warmup>30||repeats<1||repeats>5||rules<1000||rules>10000)throw new IllegalArgumentException("Out of bounded test scope");
        if(hitRules<1||hitRules>rules||!Set.of("mixed","flat","oscillate").contains(path)||Arrays.stream(rates).anyMatch(r->r<1||r>2000)||Arrays.stream(stockCounts).anyMatch(n->n!=1&&n!=5&&n!=15)||1L*Arrays.stream(rates).max().orElse(0)*Arrays.stream(stockCounts).max().orElse(0)*seconds>300000)throw new IllegalArgumentException("Bounded injection configuration required");
        Files.createDirectories(output);
    }
    void run()throws Exception {
        long began=System.nanoTime();password=UUID.randomUUID().toString();
        try(var mysql=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("pp_real_pipeline_bench").withUsername("root").withPassword(password)
                .withCommand("--performance-schema=ON","--innodb-buffer-pool-size=134217728","--max-connections=30","--slow-query-log=ON","--long-query-time=0.05","--log-output=TABLE")
                .withCreateContainerCmdModifier(c->c.getHostConfig().withNanoCPUs(1_000_000_000L).withMemory(768L*1024*1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(3306))));
            var cache=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379)
                .withCommand("redis-server","--save","","--appendonly","no","--requirepass",password)
                .withCreateContainerCmdModifier(c->c.getHostConfig().withNanoCPUs(500_000_000L).withMemory(256L*1024*1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),new com.github.dockerjava.api.model.ExposedPort(6379))))){
            mysql.start();cache.start();mysqlUrl=mysql.getJdbcUrl();
            pool=new HikariDataSource();pool.setJdbcUrl(mysqlUrl);pool.setUsername(mysql.getUsername());pool.setPassword(password);
            pool.setMaximumPoolSize(10);pool.setMinimumIdle(1);pool.setConnectionTimeout(10000);pool.setPoolName("isolated-real-pipeline");
            Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate();
            var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
            var config=new RedisStandaloneConfiguration(cache.getHost(),cache.getMappedPort(6379));config.setPassword(password);
            var factory=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().clientResources(resources).build());factory.afterPropertiesSet();factory.start();
            redis=new StringRedisTemplate(factory);
            try {
                build();
                var environment=new LinkedHashMap<String,Object>();
                environment.put("startedAtUtc",Instant.now().toString());environment.put("java",System.getProperty("java.runtime.version"));
                environment.put("os",System.getProperty("os.name"));environment.put("arch",System.getProperty("os.arch"));
                environment.put("visibleProcessors",Runtime.getRuntime().availableProcessors());environment.put("maxHeap",Runtime.getRuntime().maxMemory());
                environment.put("mysql",jdbc.queryForObject("SELECT VERSION()",String.class));environment.put("mysqlCpu",1);environment.put("redisCpu",0.5);
                environment.put("mysqlMemoryMiB",768);environment.put("redisMemoryMiB",256);environment.put("hikariMaximum",10);environment.put("queueCapacity",CAPACITY);
                environment.put("innodbFlushLogAtTrxCommit",jdbc.queryForObject("SELECT @@innodb_flush_log_at_trx_commit",Integer.class));
                environment.put("syncBinlog",jdbc.queryForObject("SELECT @@sync_binlog",Integer.class));environment.put("seed",seed);environment.put("suite",suite);
                environment.put("durationSeconds",seconds);environment.put("warmupSeconds",warmup);environment.put("repeats",repeats);environment.put("rulesPerStock",rules);
                environment.put("ratesPerStock",rates);environment.put("stockCounts",stockCounts);environment.put("hitRules",hitRules);environment.put("path",path);environment.put("instrumentation",PipelineProbe.detailed?"full":"boundary");
                environment.put("candidateIdsCaptured",PipelineProbe.captureCandidateIds);environment.put("evaluatorReference",System.getenv().getOrDefault("PP_PIPELINE_EVALUATION_REF","working-tree"));
                environment.put("commit",System.getenv().getOrDefault("PP_PIPELINE_COMMIT","unknown"));environment.put("sources",sourceHashes());
                environment.put("entry","Real MarketQuoteLane -> transactional production QuoteIngestionService; no Spring application or provider socket");
                environment.put("eventOrigin",EVENT_ORIGIN.toString());environment.put("measuredOrigin",EVENT_ORIGIN.plusSeconds(warmup+121).toString());
                save("environment.json",environment);
                if(suite.equals("correctness"))correctness();
                else {
                    List<Case> cases=cases();
                    for(Case c:cases){results.add(trial(c));save("summary.json",Map.of("environment",environment,"cases",results,"checks",checks,"complete",false));}
                    if(suite.equals("smoke"))correctness();
                }
                save("summary.json",Map.of("environment",environment,"cases",results,"checks",checks,"complete",true,"elapsedSeconds",(System.nanoTime()-began)/1e9));
                System.out.println("PIPELINE_COMPLETE cases="+results.size()+" checks="+checks.size()+" seconds="+(System.nanoTime()-began)/1e9);
            }finally{meters.close();factory.destroy();resources.shutdown();pool.close();}
        }
    }
    void build()throws Exception {
        DataSource measured=PipelineProbe.dataSource(pool);jdbc=new JdbcTemplate(pool);tx=new DataSourceTransactionManager(measured);
        Configuration cfg=new Configuration();cfg.setMapUnderscoreToCamelCase(true);cfg.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        for(Class<?> type:List.of(StockMapper.class,QuoteEventMapper.class,MarketDataMapper.class,AlertRuleMapper.class,AlertTriggerMapper.class,OutboxMapper.class,NotificationMapper.class,com.ezra.pricepulse.user.UserMapper.class,com.ezra.pricepulse.monitoring.MonitoringMapper.class))cfg.addMapper(type);
        SqlSessionFactoryBean fb=new SqlSessionFactoryBean();fb.setDataSource(measured);fb.setConfiguration(cfg);fb.afterPropertiesSet();sessions=new SqlSessionTemplate(Objects.requireNonNull(fb.getObject()));
        stocks=PipelineProbe.mapper(StockMapper.class,sessions.getMapper(StockMapper.class));ruleMapper=PipelineProbe.mapper(AlertRuleMapper.class,sessions.getMapper(AlertRuleMapper.class));
        index=new RuleIndex(new ThresholdIndex(redis),ruleMapper,stocks,meters){
            @Override public List<Long> candidates(Stock s,BigDecimal p,BigDecimal c){long t=System.nanoTime();try{var ids=super.candidates(s,p,c);if(PipelineProbe.captureCandidateIds&&PipelineProbe.CURRENT.get()!=null)PipelineProbe.CURRENT.get().candidateIds=ids;return ids;}finally{PipelineProbe.record("redis_candidate",t);}}
            @Override public void changed(Stock s,List<AlertRule> r){long t=System.nanoTime();try{super.changed(s,r);}finally{PipelineProbe.record("index_maintenance",t);}}
        };
        outboxWriter=PipelineProbe.transactional(new OutboxWriter(sessions.getMapper(OutboxMapper.class),json),tx,false);
        var evaluation=PipelineProbe.transactional(new AlertEvaluationService(ruleMapper,sessions.getMapper(AlertTriggerMapper.class),index,outboxWriter,json),tx,false);
        ingestion=PipelineProbe.transactional(new QuoteIngestionService(PipelineProbe.mapper(QuoteEventMapper.class,sessions.getMapper(QuoteEventMapper.class)),stocks,
                new MarketProperties(Duration.ofMinutes(2),Duration.ofSeconds(30)),clock,evaluation,sessions.getMapper(MarketDataMapper.class)),tx,true);
        notifications=PipelineProbe.transactional(new NotificationService(sessions.getMapper(NotificationMapper.class),json,clock),tx,false);
    }
    List<Case> cases() {
        List<Case> list=new ArrayList<>();
        if(suite.equals("smoke")){
            for(Scenario s:List.of(Scenario.ZERO_RULES,Scenario.ZERO_HIT,Scenario.FEW_HIT,Scenario.BURST_100,Scenario.BURST_500,Scenario.BURST_1000))list.add(new Case(s.name(),s,1,100,seconds,1));
            list.add(new Case("five",Scenario.FEW_HIT,5,50,seconds,1));list.add(new Case("fifteen",Scenario.ZERO_HIT,15,50,seconds,1));
        } else if(suite.equals("sustain")) {
            for(int r=1;r<=repeats;r++)for(Scenario s:selectedScenarios)for(int n:stockCounts)for(int rate:rates)
                list.add(new Case("sustain-"+s+"-"+n+"stocks-"+rate+"-"+r,s,n,rate,seconds,r));
        } else {
            for(int r=1;r<=repeats;r++) {
                if(Arrays.stream(stockCounts).anyMatch(n->n==1))for(Scenario s:selectedScenarios)for(int rate:rates)list.add(new Case(s+"-"+rate+"-"+r,s,1,rate,seconds,r));
                for(int n:stockCounts)if(n>1)for(Scenario s:selectedScenarios.stream().filter(s->s==Scenario.ZERO_HIT||s==Scenario.FEW_HIT).toList())for(int rate:Arrays.stream(rates).filter(v->v<=200).toArray())
                    list.add(new Case(s+"-"+n+"stocks-"+rate+"-"+r,s,n,rate,seconds,r));
            }
        }
        return list;
    }
    List<Long> seed(Scenario scenario,int count)throws Exception {
        if(!pool.getJdbcUrl().equals(mysqlUrl)||!mysqlUrl.contains("pp_real_pipeline_bench"))throw new IllegalStateException("Isolation guard failed");
        // Only this freshly created disposable database. No command-line DB override exists.
        try(var c=pool.getConnection();var statement=c.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for(String table:jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",String.class))statement.execute("TRUNCATE TABLE `"+table+"`");
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        try(var c=redis.getConnectionFactory().getConnection()){c.serverCommands().flushDb();}
        jdbc.update("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'synthetic@example.invalid','not-a-password','SYNTHETIC ONLY')");
        List<Long> ids=new ArrayList<>();
        for(int s=0;s<count;s++){
            long id=s+1;ids.add(id);jdbc.update("INSERT INTO stocks(id,symbol,name,market,currency,rule_index_token) VALUES(?,?,'SYNTHETIC ONLY','US','USD',?)",id,"PPTEST"+s,UUID.nameUUIDFromBytes((seed+":"+scenario+":"+s).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            int target=target(scenario),total=scenario==Scenario.ZERO_RULES?0:rules;
            try(var c=pool.getConnection();var p=c.prepareStatement("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status,version,created_at,effective_at) VALUES(?,1,?,?,?,?,?,?,?,0,'2026-10-29 00:00:00','2026-10-29 00:00:00')")) {
                c.setAutoCommit(false);
                for(int r=0;r<total;r++){
                    boolean special=r<target,above=r%2==0;String status=r%4<2?"ACTIVE":"COOLDOWN";
                    BigDecimal threshold=above?money("200"):money("50"),rearm=above?money("80"):money("120");String mode="REPEAT";
                    if(scenario==Scenario.FEW_HIT&&special){threshold=above?money("100.05"):money("99.95");rearm=above?money("99.95"):money("100.05");status="ACTIVE";}
                    if(scenario.name().startsWith("BURST")&&special){above=true;threshold=money("105");rearm=null;status="ACTIVE";mode="ONCE";}
                    p.setLong(1,s*100000L+r+1);p.setLong(2,id);p.setString(3,"rule-"+r);p.setString(4,above?"ABOVE":"BELOW");p.setBigDecimal(5,threshold);p.setString(6,mode);p.setBigDecimal(7,rearm);p.setString(8,status);p.addBatch();
                }
                p.executeBatch();c.commit();
            }
        }
        return ids;
    }
    int target(Scenario s){return switch(s){case FEW_HIT->hitRules;case BURST_100->100;case BURST_500->500;case BURST_1000->1000;default->0;};}
    static BigDecimal money(String p){return new BigDecimal(p).setScale(4);}
    BigDecimal price(Scenario s,int n,int burstAt) {
        if(n==burstAt&&s.name().startsWith("BURST"))return money("110");
        if(path.equals("flat"))return money("100");if(path.equals("oscillate"))return n%2==0?money("99.90"):money("100.10");
        int part=Math.floorMod(n,20);
        if(part<2)return money("99.90");if(part==3||part==4)return money("100.10");
        long value=new SplittableRandom(seed+n).nextInt(-300,301);
        return BigDecimal.valueOf(1000000+value,4);
    }
    Map<String,Object> trial(Case c)throws Exception {
        caseNumber++;clock.reset();List<Long> ids=seed(c.scenario,c.stocks);
        var samples=new ConcurrentHashMap<String,PipelineProbe.Sample>();var overflow=new AtomicInteger();var maxDepth=new AtomicInteger();var maxDiscardWait=new AtomicLong();
        var laneMap=new LinkedHashMap<Integer,MarketQuoteLane>();var firstError=new AtomicReference<String>();
        var errors=new ConcurrentHashMap<String,Long>();
        int burstAt=Math.max(3,c.rate*c.seconds/3);
        for(int s=0;s<c.stocks;s++) {
            int stock=s;
            var observer=new MarketQuoteLane.Observer(){
                public void enqueued(MarketQuoteLane.Work w,long at){var v=samples.get(w.trade().eventKey());v.enqueuedNanos=at;if(v.measured)maxDepth.accumulateAndGet(laneMap.get(stock).sample().depth(),Math::max);}
                public void started(MarketQuoteLane.Work w,long enqueued,long at){var v=samples.get(w.trade().eventKey());v.enqueuedNanos=enqueued;v.startedNanos=at;PipelineProbe.CURRENT.set(v);v.binding=QuoteTiming.bind(PipelineProbe.detailed?v:null);}
                public void completed(MarketQuoteLane.Work w,long at,boolean ok){
                    var v=samples.get(w.trade().eventKey());v.completedNanos=System.nanoTime();v.failed=!ok;
                    v.record("quote_process_total",v.completedNanos-at);v.record("queue_wait",at-v.enqueuedNanos);v.record("trade_processing_lag",v.completedNanos-v.receivedNanos);
                    try{v.binding.close();}catch(Exception e){throw new IllegalStateException(e);}finally{PipelineProbe.CURRENT.remove();v.finished=true;}
                }
                public void discarded(MarketQuoteLane.Work w,String cause){var v=samples.get(w.trade().eventKey());v.discarded=cause;if(v.measured)maxDiscardWait.accumulateAndGet(System.nanoTime()-v.enqueuedNanos,Math::max);}
            };
            laneMap.put(s,new MarketQuoteLane(ids.get(s),CAPACITY,w->{
                var t=w.trade();var sample=PipelineProbe.CURRENT.get();
                try {
                    var result=ingestion.ingest(new QuoteCandidate(t.eventKey(),ids.get(stock),t.price(),MarketTime.utc(t.eventTime()),"ALPACA_IEX",null,t.receivedAt(),"ALPACA","iex",w.continuity()+":2026-10-30",w.reason()),QuoteIngestionPolicy.LIVE);
                    sample.outcome=result.outcome().name();sample.baselineReason=result.reason();
                    if(result.matching()!=null){sample.candidates=result.matching().candidates();sample.triggers=result.matching().triggered();sample.rearms=result.matching().rearmed();}
                }catch(RuntimeException e){firstError.compareAndSet(null,e.getClass().getSimpleName());errors.merge(diagnostic(e),1L,Long::sum);throw e;}
            },code->{if(code.equals("QUEUE_OVERFLOW"))overflow.incrementAndGet();else firstError.compareAndSet(null,code);},observer));
        }
        var resourceRows=new CopyOnWriteArrayList<Map<String,Object>>();var sampler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("pipeline-resource-sampler").factory());
        try {
            clock.begin(EVENT_ORIGIN);long warmStart=System.nanoTime();inject(c,laneMap,samples,-c.rate*warmup,c.rate*warmup,warmStart,burstAt,false);awaitDrain(laneMap,120);
            String warmupFirstError=firstError.getAndSet(null);var warmupErrors=new TreeMap<>(errors);errors.clear();
            long warmupFailures=samples.values().stream().filter(s->!s.measured&&s.failed).count();
            long warmupDiscards=samples.values().stream().filter(s->!s.measured&&s.discarded!=null).count();
            // Warm the derived index without changing rule state; first LIVE trade already established the baseline.
            for(long id:ids){var stock=stocks.findActiveById(id);index.candidates(stock,stock.latestPrice(),stock.latestPrice());}
            overflow.set(0);
            String ruleInputHash=sha(json.writeValueAsBytes(jdbc.queryForList("SELECT id,stock_id,name,direction,threshold_price,rearm_price,trigger_mode,status,version,last_triggered_at,created_at,effective_at FROM alert_rules ORDER BY id")));
            var before=facts();var beforeServer=server();var redisBefore=redisInfo();var jvmBefore=PipelineProbe.jvm();
            clock.begin(EVENT_ORIGIN.plusSeconds(warmup+121));long start=System.nanoTime();
            sampler.scheduleAtFixedRate(()->{try{resourceRows.add(resource(laneMap,start));}catch(RuntimeException e){firstError.compareAndSet(null,"RESOURCE_SAMPLE_FAILED");errors.merge("RESOURCE:"+diagnostic(e),1L,Long::sum);}},0,1,TimeUnit.SECONDS);
            inject(c,laneMap,samples,0,c.rate*c.seconds,start,burstAt,true);long inputEnd=System.nanoTime();
            awaitDrain(laneMap,120);long end=System.nanoTime();sampler.shutdown();sampler.awaitTermination(5,TimeUnit.SECONDS);
            var serverAfter=server();var afterRedis=redisInfo();var jvmAfter=PipelineProbe.jvm();var after=facts();
            List<PipelineProbe.Sample> measured=samples.values().stream().filter(s->s.measured).sorted(Comparator.comparing((PipelineProbe.Sample s)->s.stock).thenComparingInt(s->s.number)).toList();
            Map<String,Object> result=new LinkedHashMap<>();result.put("id",c.id);result.put("scenario",c.scenario);result.put("stocks",c.stocks);result.put("ratePerStock",c.rate);result.put("seconds",c.seconds);result.put("repeat",c.repeat);
            long complete=measured.stream().filter(s->s.finished&&!s.failed&&"PROCESSED".equals(s.outcome)).count(),discard=measured.stream().filter(s->s.discarded!=null).count();
            long failed=measured.stream().filter(s->s.failed).count();
            long rejected=measured.stream().filter(s->s.finished&&!s.failed&&!"PROCESSED".equals(s.outcome)).count();
            result.put("inputEvents",measured.size());result.put("processedEvents",complete);result.put("discardedTasks",discard);result.put("failedTasks",failed);result.put("queueOverflows",overflow.get());result.put("firstError",firstError.get());
            result.put("businessRejectedEvents",rejected);
            result.put("warmupFailedTasks",warmupFailures);result.put("warmupDiscardedTasks",warmupDiscards);result.put("warmupFirstError",warmupFirstError);result.put("warmupErrors",warmupErrors);result.put("errors",new TreeMap<>(errors));
            result.put("initialRulesSha256",ruleInputHash);
            result.put("finalRulesSha256",sha(json.writeValueAsBytes(jdbc.queryForList("SELECT id,stock_id,name,direction,threshold_price,rearm_price,trigger_mode,status,version,last_triggered_at,created_at,effective_at FROM alert_rules ORDER BY id"))));
            result.put("inputSha256",sha(String.join("\n",samples.values().stream().sorted(Comparator.comparing(v->v.key)).map(v->v.key+","+v.trade.eventTime()+","+v.trade.price()).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            result.put("maxQueueDepth",maxDepth.get());result.put("maxDiscardedTaskWaitMs",maxDiscardWait.get()/1e6);
            if(complete+discard+failed+rejected!=measured.size())throw new IllegalStateException("Unaccounted input tasks: "+c.id);
            result.put("inputSeconds",(inputEnd-start)/1e9);result.put("drainSeconds",(end-inputEnd)/1e9);result.put("observationSeconds",(end-start)/1e9);
            result.put("actualInputPerSecond",measured.size()/((inputEnd-start)/1e9));result.put("completedPerSecondIncludingDrain",complete/((end-start)/1e9));
            var windows=new ArrayList<Map<String,Object>>();
            for(int second=0;second<(int)Math.ceil((end-start)/1e9);second++){
                final long low=start+second*1_000_000_000L,high=low+1_000_000_000L;
                windows.add(Map.of("second",second,"input",measured.stream().filter(v->v.receivedNanos>=low&&v.receivedNanos<high).count(),"processed",measured.stream().filter(v->v.finished&&!v.failed&&"PROCESSED".equals(v.outcome)&&v.completedNanos>=low&&v.completedNanos<high).count()));
            }
            result.put("rateWindows",windows);result.put("producerLateness",PipelineProbe.distribution(measured.stream().map(v->Math.max(0,v.receivedNanos-v.scheduledNanos)).toList()));
            result.put("nonemptyCandidateEvents",measured.stream().filter(s->s.candidates>0).count());result.put("triggerCount",measured.stream().mapToInt(s->s.triggers).sum());result.put("rearmCount",measured.stream().mapToInt(s->s.rearms).sum());
            result.put("candidateCount",measured.stream().mapToLong(s->s.candidates).sum());
            result.put("discardReasons",measured.stream().filter(s->s.discarded!=null).collect(java.util.stream.Collectors.groupingBy(s->s.discarded,java.util.stream.Collectors.counting())));
            var phases=new LinkedHashMap<String,Object>();
            for(String p:PipelineProbe.PHASES)phases.put(p+"_ms",PipelineProbe.distribution(measured.stream().filter(s->s.finished&&s.phases.containsKey(p)).map(s->s.phases.get(p)).toList()));
            result.put("phases",phases);result.put("hitProcess",PipelineProbe.distribution(measured.stream().filter(s->s.triggers>0).map(s->s.phases.get("quote_process_total")).toList()));
            result.put("zeroHitProcess",PipelineProbe.distribution(measured.stream().filter(s->s.finished&&!s.failed&&s.triggers==0).map(s->s.phases.get("quote_process_total")).toList()));
            var sql=new TreeMap<String,Long>();var sqlTimes=new TreeMap<String,Long>();for(var s:measured){s.sqlCounts.forEach((k,v)->sql.merge(k,v,Long::sum));s.sqlNanos.forEach((k,v)->sqlTimes.merge(k,v,Long::sum));}
            result.put("jdbcExecutions",sql);result.put("jdbcNanos",sqlTimes);result.put("serverBefore",beforeServer);result.put("serverAfter",serverAfter);
            result.put("redisBefore",redisBefore);result.put("redisAfter",afterRedis);result.put("jvmBefore",jvmBefore);result.put("jvmAfter",jvmAfter);
            var writes=new TreeMap<String,Long>();after.forEach((k,v)->writes.put(k,v-before.get(k)));result.put("tableRowDelta",writes);
            var eachStock=new ArrayList<Map<String,Object>>();
            for(int s=0;s<c.stocks;s++){final int n=s;var group=measured.stream().filter(v->v.stock==n).toList();eachStock.add(Map.of("stock",s,"processed",group.stream().filter(v->v.finished&&!v.failed&&"PROCESSED".equals(v.outcome)).count(),"queueWait",PipelineProbe.distribution(group.stream().filter(v->v.finished).map(v->v.phases.get("queue_wait")).toList())));}
            result.put("perStock",eachStock);result.put("resources",resourceRows);
            result.put("oracle",oracle(ids,c.scenario,samples.values(),burstAt));
            raw(c,measured);save("case-"+caseNumber+".json",result);
            System.out.printf(Locale.ROOT,"CASE %d %s processed=%d/%d overflow=%d discarded=%d throughput=%.1f drain=%.2fs%n",caseNumber,c.id,complete,measured.size(),overflow.get(),discard,complete/((end-start)/1e9),(end-inputEnd)/1e9);
            return result;
        }finally{sampler.shutdownNow();laneMap.values().forEach(MarketQuoteLane::close);}
    }
    void inject(Case c,Map<Integer,MarketQuoteLane> lanes,Map<String,PipelineProbe.Sample> samples,int from,int count,long start,int burstAt,boolean measured) {
        Instant origin=clock.base;
        for(int n=0;n<count;n++){
            long scheduled=start+(long)(n*1e9/c.rate);long remaining;
            while((remaining=scheduled-System.nanoTime())>0)LockSupport.parkNanos(remaining);
            for(int stock=0;stock<c.stocks;stock++){
                int number=from+n;String key="case"+caseNumber+"-s"+stock+"-n"+number;
                long received=System.nanoTime();var sample=new PipelineProbe.Sample(key,stock,number,measured,received,scheduled);samples.put(key,sample);
                var time=origin.plusNanos((long)(n*1e9/c.rate));
                sample.wallReceivedAt=Instant.now();sample.trade=new AlpacaProtocol.Trade("PPTEST"+stock,key,price(c.scenario,number,measured?burstAt:Integer.MAX_VALUE),time,clock.instant());
                lanes.get(stock).offer(sample.trade);
            }
        }
        long finish=start+(long)(count*1e9/c.rate),remaining;while((remaining=finish-System.nanoTime())>0)LockSupport.parkNanos(remaining);
    }
    static void awaitDrain(Map<Integer,MarketQuoteLane> lanes,int timeout)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
        while(System.nanoTime()<end){if(lanes.values().stream().allMatch(l->l.sample().depth()==0&&l.sample().active()==0))return;Thread.sleep(10);}
        throw new IllegalStateException("Drain timeout; do not claim sustained throughput");
    }
    Map<String,Object> resource(Map<Integer,MarketQuoteLane> lanes,long start) {
        var queues=new TreeMap<String,Object>();for(var e:lanes.entrySet()){var s=e.getValue().sample();queues.put("s"+e.getKey(),Map.of("depth",s.depth(),"oldestWaitMs",s.oldestWaitNanos()/1e6,"active",s.active()));}
        var mx=pool.getHikariPoolMXBean();
        return Map.of("second",(System.nanoTime()-start)/1e9,"queues",queues,"poolActive",mx.getActiveConnections(),"poolIdle",mx.getIdleConnections(),"poolAwaiting",mx.getThreadsAwaitingConnection(),"jvm",PipelineProbe.jvm(),
                "mysql",jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries')"));
    }
    Map<String,Object> server() {
        return Map.of("statements",jdbc.queryForList("SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_LOCK_TIME,SUM_ROWS_AFFECTED,SUM_ROWS_SENT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() AND (DIGEST_TEXT LIKE '%quote_events%' OR DIGEST_TEXT LIKE '%alert_rules%' OR DIGEST_TEXT LIKE '%stocks%' OR DIGEST_TEXT LIKE '%outbox_events%' OR DIGEST_TEXT LIKE '%market_stream_state%' OR DIGEST_TEXT LIKE '%alert_triggers%')"),
                "status",jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_commit','Com_rollback','Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries')"),
                "slowStatementRows",jdbc.queryForObject("SELECT COUNT(*) FROM mysql.slow_log",Long.class));
    }
    Map<String,String> redisInfo(){try(var c=redis.getConnectionFactory().getConnection()){var info=c.serverCommands().info("commandstats");var map=new TreeMap<String,String>();if(info!=null)info.forEach((k,v)->map.put(k.toString(),v.toString()));return map;}}
    Map<String,Long> facts(){var map=new TreeMap<String,Long>();for(String t:List.of("quote_events","alert_rules","alert_triggers","outbox_events","notifications","market_stream_state","simulation_runs","simulation_steps","simulation_rule_events"))map.put(t,jdbc.queryForObject("SELECT COUNT(*) FROM "+t,Long.class));return map;}
    Map<String,Object> oracle(List<Long> ids,Scenario scenario,Collection<PipelineProbe.Sample> samples,int burstAt)throws Exception {
        // Only possible-to-cross fixture rules; all background thresholds/rearm points lie outside the entire generated path.
        Set<String> expected=new TreeSet<>();int target=target(scenario);
        Map<String,String> baselines=new HashMap<>();
        if(target>0)jdbc.query("SELECT event_key,COALESCE(baseline_reason,'') AS reason FROM quote_events WHERE processing_status='PROCESSED'",rs->{baselines.put(rs.getString(1),rs.getString(2));});
        for(int s=0;target>0&&s<ids.size();s++) {
            final int stock=s;AlertRuleStatus[] states=new AlertRuleStatus[target];Arrays.fill(states,AlertRuleStatus.ACTIVE);BigDecimal previous=null;
            for(var v:samples.stream().filter(v->v.stock==stock&&v.finished&&!v.failed&&"PROCESSED".equals(v.outcome)).sorted(Comparator.comparingLong(v->v.startedNanos)).toList()){
                BigDecimal price=v.trade.price();
                // Read actual committed baseline flags. Generation changes must suppress active triggers.
                String baseline=baselines.get(v.key);
                for(int r=0;r<target;r++){
                    boolean burst=scenario.name().startsWith("BURST"),above=burst||r%2==0;
                    var definition=new RuleTransitionEngine.Definition(above?AlertDirection.ABOVE:AlertDirection.BELOW,burst?money("105"):above?money("100.05"):money("99.95"),burst?AlertTriggerMode.ONCE:AlertTriggerMode.REPEAT,burst?null:above?money("99.95"):money("100.05"));
                    var decision=RuleTransitionEngine.evaluate(definition,states[r],baseline.isEmpty()?previous:price,price);
                    if(decision.kind()==RuleTransitionEngine.Kind.TRIGGERED)expected.add("rule-"+r+"@"+v.key);
                    states[r]=decision.target();
                }
                previous=price;
            }
        }
        Set<String> actual=new TreeSet<>(jdbc.query("SELECT r.name,q.event_key FROM alert_triggers t JOIN alert_rules r ON r.id=t.rule_id JOIN quote_events q ON q.id=t.quote_event_id",(rs,n)->rs.getString(1)+"@"+rs.getString(2)));
        if(!actual.equals(expected))throw new IllegalStateException("Oracle mismatch "+scenario+" expected="+expected.size()+" actual="+actual.size());
        long outbox=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events",Long.class);
        if(outbox!=actual.size()||jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Long.class)!=0)throw new IllegalStateException("Outbox or notification isolation failure");
        return Map.of("expectedTriggers",expected.size(),"actualTriggers",actual.size(),"mismatches",0,"sha256",sha(String.join("\n",actual).getBytes()),"productionOutboxWrittenOnlyInDisposableDatabase",true);
    }
    void raw(Case c,List<PipelineProbe.Sample> samples)throws Exception {
        try(var writer=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(output.resolve("samples-"+caseNumber+".csv.gz"))),java.nio.charset.StandardCharsets.UTF_8))){
            writer.write("key,stock,number,event_time_utc,received_fixture_utc,received_wall_utc,price,received_nanos,scheduled_nanos,enqueued_nanos,started_nanos,completed_nanos,outcome,discarded,failed,candidates,triggers,rearms,candidate_ids_sha256");for(String p:PipelineProbe.PHASES)writer.write(","+p+"_nanos");writer.newLine();
            for(var s:samples){String idsHash=PipelineProbe.captureCandidateIds?sha(String.join(",",s.candidateIds.stream().sorted().map(Object::toString).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8)):"";writer.write(s.key+","+s.stock+","+s.number+","+s.trade.eventTime()+","+s.trade.receivedAt()+","+s.wallReceivedAt+","+s.trade.price()+","+s.receivedNanos+","+s.scheduledNanos+","+s.enqueuedNanos+","+s.startedNanos+","+s.completedNanos+","+Objects.toString(s.outcome,"")+","+Objects.toString(s.discarded,"")+","+s.failed+","+s.candidates+","+s.triggers+","+s.rearms+","+idsHash);for(String p:PipelineProbe.PHASES)writer.write(","+s.phases.getOrDefault(p,0L));writer.newLine();}
        }
    }
    void correctness()throws Exception {
        new PipelineCorrectness(this).run();
    }
    void save(String file,Object data)throws Exception{json.writerWithDefaultPrettyPrinter().writeValue(output.resolve(file).toFile(),data);}
    Map<String,String> sourceHashes()throws Exception{Path sourceRoot=Path.of(System.getenv().getOrDefault("PP_PIPELINE_SOURCE_ROOT","."));var map=new TreeMap<String,String>();for(String p:List.of("src/main/java/com/ezra/pricepulse/market/MarketQuoteLane.java","src/main/java/com/ezra/pricepulse/market/QuoteIngestionService.java","src/main/java/com/ezra/pricepulse/market/QuoteTiming.java","src/main/java/com/ezra/pricepulse/alert/AlertEvaluationService.java","src/main/java/com/ezra/pricepulse/matching/ThresholdIndex.java","src/test/java/com/ezra/pricepulse/market/RealPipelineExperiment.java","src/test/java/com/ezra/pricepulse/market/PipelineProbe.java"))map.put(p,sha(Files.readAllBytes(sourceRoot.resolve(p))));return map;}
    static String sha(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static String diagnostic(Throwable e){var chain=new ArrayList<String>();for(int depth=0;e!=null&&depth<6;depth++,e=e.getCause()){String text=e.getClass().getSimpleName();if(e instanceof SQLException sql)text+="[state="+sql.getSQLState()+",code="+sql.getErrorCode()+"]";chain.add(text);}return String.join("/",chain);}
    static final class EventClock extends Clock {
        volatile long origin=System.nanoTime();volatile Instant base=EVENT_ORIGIN;void reset(){begin(EVENT_ORIGIN);}
        void begin(Instant at){base=at;origin=System.nanoTime();}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return base.plusNanos(System.nanoTime()-origin);}
    }
}
