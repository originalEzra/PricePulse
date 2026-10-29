package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.PricePulseApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Captured input in disposable databases, never connected to the user's development DB or MQ. */
@EnabledIfEnvironmentVariable(named="PRICEPULSE_RUN_SAMPLE_CAPACITY",matches="true")
@SpringBootTest(classes=PricePulseApplication.class,properties={
        "price-pulse.alpaca.enabled=false", "price-pulse.alpaca.business-writes-enabled=false",
        "price-pulse.assistant.enabled=false", "price-pulse.messaging.enabled=false",
        "price-pulse.security.jwt.secret=sample-capacity-test-only-secret-at-least-32-bytes",
        "spring.datasource.hikari.maximum-pool-size=5"})
@ActiveProfiles("integration")
@Import(RealQuoteTestSupport.class)
@Testcontainers
class MarketSampleCapacityIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("sample_capacity");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired QuoteIngestionService ingestion;
    @Autowired RealQuoteTestSupport.FixtureClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final Map<String,MarketQuoteLane> lanes=new LinkedHashMap<>();
    final Map<String,Long> stocks=new LinkedHashMap<>();
    final Map<AlpacaProtocol.Trade,Long> offered=Collections.synchronizedMap(new IdentityHashMap<>());
    final List<Double> transactions=Collections.synchronizedList(new ArrayList<>()), queueWait=Collections.synchronizedList(new ArrayList<>()), endToEnd=Collections.synchronizedList(new ArrayList<>());
    final Map<String,AtomicLong> outcomes=new ConcurrentHashMap<>(), faults=new ConcurrentHashMap<>();
    final Set<String> baselines=ConcurrentHashMap.newKeySet();
    final AtomicInteger completed=new AtomicInteger(), triggered=new AtomicInteger(), rearmed=new AtomicInteger();
    final AtomicInteger baselineTriggers=new AtomicInteger();

    @Test @Timeout(180)
    void capturedFiveStockInputAtTenTimesSpeedUsesRealStockLocksAndTransactions() throws Exception {
        Path input=Path.of("target/v2-p2/open-session-trades.json");
        var samples=List.of(json.readValue(input.toFile(),AlpacaOpenSessionReadOnlyIT.Sample[].class));
        assertThat(samples).isNotEmpty();
        var symbols=new TreeSet<String>();samples.forEach(s->symbols.add(s.symbol()));assertThat(symbols).hasSize(5);
        jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES('capacity@test.local','test','Capacity')");
        long owner=jdbc.queryForObject("SELECT id FROM users WHERE email='capacity@test.local'",Long.class);
        int rulesPerStock=100;
        for(String symbol:symbols) {
            long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol=? AND market='US'",Long.class,symbol);stocks.put(symbol,stock);
            var prices=samples.stream().filter(s->s.symbol().equals(symbol)).map(s->new BigDecimal(s.price())).sorted().toList();
            BigDecimal low=prices.getFirst(),span=prices.getLast().subtract(low);
            for(int i=1;i<=rulesPerStock;i++) {
                BigDecimal threshold=low.add(span.multiply(BigDecimal.valueOf(i)).divide(BigDecimal.valueOf(rulesPerStock+1),4,RoundingMode.HALF_UP));
                jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status) VALUES(?,?,'Capacity','ABOVE',?,'REPEAT',?,'ACTIVE')",
                        owner,stock,threshold,threshold.subtract(new BigDecimal("0.0001")));
            }
            lanes.put(symbol,new MarketQuoteLane(stock,1024,work->process(symbol,stock,work),code->faults.computeIfAbsent(code,k->new AtomicLong()).incrementAndGet()));
        }
        Instant started=Instant.now();long wallStarted=System.nanoTime();int totalOffered=0;
        try {
            var groups=new LinkedHashMap<Integer,List<AlpacaOpenSessionReadOnlyIT.Sample>>();
            samples.forEach(s->groups.computeIfAbsent(s.connection(),k->new ArrayList<>()).add(s));
            for(var group:groups.values()) {
                lanes.values().forEach(l->l.gap("CAPTURED_CONNECTION_BASELINE"));
                long start=System.nanoTime();Instant first=group.getFirst().receivedAt();
                for(var sample:group) {
                    long target=start+Duration.between(first,sample.receivedAt()).toNanos()/10;
                    long remaining=target-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
                    var trade=new AlpacaProtocol.Trade(sample.symbol(),sample.eventKey(),new BigDecimal(sample.price()),sample.eventTime(),sample.receivedAt());
                    offered.put(trade,System.nanoTime());lanes.get(sample.symbol()).offer(trade);totalOffered++;
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
                while(completed.get()<totalOffered && System.nanoTime()<deadline)Thread.sleep(20);
                if(completed.get()!=totalOffered)break;
            }
        } finally {
            lanes.values().forEach(MarketQuoteLane::close);
            var report=new LinkedHashMap<String,Object>();
            report.put("startedAt",started.toString());report.put("finishedAt",Instant.now().toString());
            report.put("inputSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
            report.put("inputSamples",samples.size());report.put("offered",totalOffered);report.put("completed",completed.get());
            report.put("environment","DISPOSABLE_MYSQL_8.4.7_REDIS_7.4.6");report.put("developmentDatabaseConnected",false);report.put("providerConnected",false);
            report.put("formalBusinessWritesEnabled",false);report.put("mqConnected",false);report.put("realAlertEndToEndValidated",false);
            report.put("rulesPerStock",rulesPerStock);report.put("symbols",symbols);report.put("queueCapacityPerStock",1024);report.put("replaySpeedMultiplier",10);
            report.put("clock","captured receivedAt plus measured test queue wait; original eventTime unchanged");
            report.put("outcomes",counts(outcomes));report.put("faults",counts(faults));report.put("baselines",baselines.size());report.put("baselineTriggers",baselineTriggers.get());
            report.put("triggered",triggered.get());report.put("rearmed",rearmed.get());
            report.put("transactionIncludingCommitMs",AlpacaOpenSessionReadOnlyIT.percentiles(transactions));
            report.put("queueWaitMs",AlpacaOpenSessionReadOnlyIT.percentiles(queueWait));report.put("offerToCommitMs",AlpacaOpenSessionReadOnlyIT.percentiles(endToEnd));
            double elapsed=(System.nanoTime()-wallStarted)/1e9;report.put("elapsedSeconds",elapsed);report.put("completedPerWallSecond",completed.get()/elapsed);
            report.put("databaseQuotes",jdbc.queryForObject("SELECT COUNT(*) FROM quote_events",Long.class));
            report.put("databaseTriggers",jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers",Long.class));
            report.put("databaseOutbox",jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events",Long.class));
            report.put("databaseNotifications",jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Long.class));
            report.put("limits",List.of("CONTROLLED_CAPTURE_REPLAY_NOT_LIVE_BUSINESS_PROCESSING","NO_CONCURRENT_RULE_EDITS_OR_HISTORY_SYNC","NOT_MAXIMUM_OR_SUSTAINED_CAPACITY","TRANSACTION_TIME_INCLUDES_STOCK_LOCK_QUERY_NOT_ISOLATED_LOCK_WAIT"));
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/v2-p2/sample-capacity.json").toFile(),report);
        }
        assertThat(faults).isEmpty();assertThat(completed.get()).isEqualTo(samples.size());assertThat(baselineTriggers.get()).isZero();
        long triggers=jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers",Long.class);
        assertThat(triggers).isEqualTo(triggered.get());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events",Long.class)).isEqualTo(triggers);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulation_runs",Long.class)).isZero();
    }
    private void process(String symbol,long stock,MarketQuoteLane.Work work) {
        var trade=work.trade();long begin=System.nanoTime(),queued=begin-offered.get(trade);
        queueWait.add(queued/1e6);boolean baseline=baselines.add(symbol+":"+work.continuity());
        try {
            var result=clock.at(MarketTime.utc(trade.receivedAt().plusNanos(queued)),()->ingestion.ingest(new QuoteCandidate(
                    trade.eventKey(),stock,trade.price(),LocalDateTime.ofInstant(trade.eventTime(),ZoneOffset.UTC),"ALPACA_IEX",null,
                    trade.receivedAt(),"ALPACA","iex",work.continuity(),work.reason()),QuoteIngestionPolicy.LIVE));
            outcomes.computeIfAbsent(result.outcome().name(),k->new AtomicLong()).incrementAndGet();
            if(result.matching()!=null) {
                triggered.addAndGet(result.matching().triggered());rearmed.addAndGet(result.matching().rearmed());
                if(baseline)baselineTriggers.addAndGet(result.matching().triggered());
            }
        } catch(RuntimeException error) {
            faults.computeIfAbsent("INGESTION_FAILURE",k->new AtomicLong()).incrementAndGet();throw error;
        } finally {
            long end=System.nanoTime();transactions.add((end-begin)/1e6);endToEnd.add((end-offered.get(trade))/1e6);completed.incrementAndGet();
        }
    }
    private Map<String,Long> counts(Map<String,AtomicLong> values) {
        var result=new TreeMap<String,Long>();values.forEach((k,v)->result.put(k,v.get()));return result;
    }
}
