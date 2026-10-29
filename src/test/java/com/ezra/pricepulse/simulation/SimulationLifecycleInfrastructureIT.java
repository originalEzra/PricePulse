package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.matching.ThresholdIndex;
import com.ezra.pricepulse.simulation.SimulationApi.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"price-pulse.alpaca.enabled=false","price-pulse.messaging.enabled=false"})
@ActiveProfiles("integration") @Import(RealQuoteTestSupport.class) @Testcontainers
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class SimulationLifecycleInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("simulation_lifecycle");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"simulation-lifecycle-test-only-secret-at-least-32-bytes");
    }
    @Autowired SimulationService service;
    @Autowired SimulationMapper store;
    @Autowired MarketDataMapper data;
    @Autowired MarketHistoryCache cache;
    @Autowired RealQuoteTestSupport.FixtureClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwt;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ThresholdIndex index;
    long stock,bar;
    static final Instant ORIGIN=Instant.parse("2024-10-29T13:31:00Z");

    @BeforeEach void marketFixture() {
        stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='AAPL' AND market='US'",Long.class);
        jdbc.update("DELETE FROM market_stream_state");jdbc.update("DELETE FROM market_price_bars");jdbc.update("DELETE FROM market_sessions");
        var sessions=new ArrayList<Session>();
        for(String date:List.of("2024-10-28","2024-10-29","2024-10-30","2024-10-31","2024-11-01","2024-11-04","2024-11-05")) {
            var day=LocalDate.parse(date);var zone=MarketTime.NEW_YORK;
            sessions.add(new Session(day,MarketTime.utc(ZonedDateTime.of(day,LocalTime.of(9,30),zone).toInstant()),MarketTime.utc(ZonedDateTime.of(day,LocalTime.of(16,0),zone).toInstant())));
        }
        var bars=new ArrayList<Bar>();
        for(var session:sessions.subList(0,2))for(int i=0;i<390;i++) {
            var at=session.opensAt().plusMinutes(i);var price=new BigDecimal("100.0000");
            bars.add(new Bar(null,stock,at,at.plusMinutes(1),price,price,price,price,1,MarketTime.utc(ORIGIN.plusSeconds(86400))));
        }
        cache.store(new AlpacaDataClient.Download(sessions,bars,1),List.of(stock),sessions.getFirst().open(),sessions.get(1).close());
        bar=jdbc.queryForObject("SELECT id FROM market_price_bars WHERE stock_id=? AND interval_end=?",Long.class,stock,MarketTime.utc(ORIGIN));
    }

    @Test void apiEnforcesAuthenticationHeadersExplicitExpectedRunAndTrustedSourceOnly() throws Exception {
        long owner=user();String auth=token(owner);
        mvc.perform(get("/api/simulations/current")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/simulations/current").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        var body=json.valueToTree(request(null));
        mvc.perform(post("/api/simulations/start").header("Authorization",auth).contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).remove("expectedCurrentRunId");
        mvc.perform(post("/api/simulations/start").header("Authorization",auth).header("Idempotency-Key",key()).contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        body=json.valueToTree(request(null));((com.fasterxml.jackson.databind.node.ObjectNode)body).put("userId",user()).put("initialPrice",1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("seed","9223372036854775806");
        mvc.perform(post("/api/simulations/start").header("Authorization",auth).header("Idempotency-Key",key()).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.origin.point.price").value("100.0000"))
                .andExpect(jsonPath("$.data.seed").value("9223372036854775806"));
        assertThat(service.current(owner)).isNotNull();
        mvc.perform(post("/api/market/simulate").header("Authorization",auth).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void startFreezesRealPrefixCalendarOwnRulesAndD1AndRetriesBeforeRevalidatingRevisedData() {
        long owner=user(),outsider=user();
        rule(owner,"ONCE","ACTIVE");rule(owner,"REPEAT","COOLDOWN");rule(owner,"ONCE","COMPLETED");rule(owner,"ONCE","PAUSED");rule(owner,"ONCE","DELETED");rule(outsider,"ONCE","ACTIVE");
        var request=new Start(stock,request(null).origin(),SimulationGenerator.Mode.RISE,new BigDecimal("0.003"),null,null);
        String key=key();Current run=service.start(owner,request,key);
        assertThat(run.ruleSnapshots()).hasSize(4);
        assertThat(run.ruleSnapshots()).extracting(Rules::initialState).containsExactly(
                com.ezra.pricepulse.alert.AlertRuleStatus.ACTIVE,com.ezra.pricepulse.alert.AlertRuleStatus.ACTIVE,
                com.ezra.pricepulse.alert.AlertRuleStatus.ACTIVE,com.ezra.pricepulse.alert.AlertRuleStatus.PAUSED);
        var frozen=service.timeline(owner,run.runId(),null,null,null);String calendar=store.current(owner).calendarSnapshot();
        assertThat(frozen.realPrefix()).hasSize(391);assertThat(frozen.realPrefix().getLast().pointRef()).isEqualTo("bar:"+bar);
        jdbc.update("UPDATE alert_rules SET name='edited',threshold_price=999,status='DELETED',version=version+1 WHERE user_id=?",owner);
        jdbc.update("UPDATE market_price_bars SET close_price=101,high_price=101,revision=revision+1 WHERE id=?",bar);
        jdbc.update("UPDATE market_sessions SET closes_at=DATE_SUB(closes_at,INTERVAL 1 HOUR)");
        var retry=service.start(owner,request,key);assertThat(retry.runId()).isEqualTo(run.runId());assertThat(retry.seed()).isEqualTo(run.seed());
        assertThat(store.current(owner).calendarSnapshot()).isEqualTo(calendar);
        assertThat(service.timeline(owner,run.runId(),null,null,null).realPrefix()).isEqualTo(frozen.realPrefix());
        assertThat(service.current(owner).ruleSnapshots().stream().map(Rules::config).toList()).isEqualTo(run.ruleSnapshots().stream().map(Rules::config).toList());
        assertThatThrownBy(()->service.start(owner,new Start(stock,request.origin(),SimulationGenerator.Mode.FALL,request.stepRatio(),null,null),key)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.start(owner,request(run.runId()),key())).isInstanceOf(BusinessException.class);
        assertThat(service.current(owner).runId()).isEqualTo(run.runId());
    }
    @Test void invalidPointRevisionOtherStockFutureLegacyAndUnavailableLatestPreserveSavedExperiment() {
        long owner=user();Current run=service.start(owner,request(null),key());
        long other=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='MSFT'",Long.class);
        assertCode(()->service.start(owner,new Start(other,request(null).origin(),SimulationGenerator.Mode.NORMAL,new BigDecimal("0.003"),42L,run.runId()),key()),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.start(owner,new Start(stock,new Origin(OriginKind.HISTORICAL_POINT,"bar:"+bar,"999"),SimulationGenerator.Mode.NORMAL,new BigDecimal("0.003"),42L,run.runId()),key()),ErrorCode.CONFLICT);
        assertCode(()->clock.at(MarketTime.utc(ORIGIN.minusNanos(1)),()->service.start(owner,request(run.runId()),key())),ErrorCode.BUSINESS_RULE_VIOLATION);
        jdbc.update("INSERT INTO quote_events(event_key,stock_id,price,event_time,source,processing_status,origin_type,event_time_ns) VALUES(?,?,1,?,'LEGACY','PROCESSED','LEGACY',?)",key(),stock,MarketTime.utc(ORIGIN),MarketTime.nanos(ORIGIN));
        long legacy=jdbc.queryForObject("SELECT MAX(id) FROM quote_events",Long.class);
        assertCode(()->service.start(owner,new Start(stock,new Origin(OriginKind.HISTORICAL_POINT,"trade:"+legacy,"1"),SimulationGenerator.Mode.NORMAL,new BigDecimal("0.003"),42L,run.runId()),key()),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.start(owner,latest(run.runId()),key()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(service.current(owner).runId()).isEqualTo(run.runId());
    }
    @Test void acceptedLatestPreservesNanosecondsAndRejectsStaleTradeInsteadOfUsingHistory() {
        long owner=user();Instant at=ORIGIN.plusNanos(123456789);
        jdbc.update("INSERT INTO quote_events(event_key,stock_id,price,event_time,source,processing_status,origin_type,event_time_ns,provider,feed,accepted_sequence,processed_at) VALUES(?,?,100,?,'ALPACA_IEX','PROCESSED','REAL',?,'ALPACA','iex',1,?)",
                key(),stock,MarketTime.utc(at),MarketTime.nanos(at),MarketTime.utc(at));
        data.saveStreamState(new StreamState(stock,"ALPACA","iex",MarketTime.nanos(at),1,"test-connection",MarketTime.utc(at),MarketTime.utc(at)));
        var run=clock.at(MarketTime.utc(at.plusSeconds(1)),()->service.start(owner,latest(null),key()));
        assertThat(run.simClock()).isEqualTo(at);assertThat(run.origin().path("point").path("kind").asText()).isEqualTo("TRADE");
        var batch=service.advance(owner,run.runId(),new Advance(0L,0L),key());
        assertThat(batch.toClock()).isEqualTo(at.plusSeconds(3600));assertThat(batch.points().getFirst().eventTime().getNano()).isEqualTo(at.getNano());
        assertCode(()->clock.at(MarketTime.utc(at.plusSeconds(121)),()->service.start(owner,latest(run.runId()),key())),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(service.current(owner).runId()).isEqualTo(run.runId());
    }
    @Test void batchesAreDeterministicIdempotentOwnedAndNeverMutateFormalFacts() {
        long a=user(),b=user();rule(a,"ONCE","ACTIVE");rule(b,"ONCE","ACTIVE");
        var ra=service.start(a,request(null),key());var rb=service.start(b,request(null),key());var before=facts();String firstKey=key();
        var first=service.advance(a,ra.runId(),new Advance(0L,0L),firstKey);
        var other=service.advance(b,rb.runId(),new Advance(0L,0L),key());
        assertThat(first.points()).isEqualTo(other.points());assertThat(first.points()).hasSize(60);assertThat(first.triggered()).isEqualTo(1);
        assertThat(service.advance(a,ra.runId(),new Advance(1L,1L),key()).versionAfter()).isEqualTo(2);
        assertThat(service.advance(a,ra.runId(),new Advance(0L,0L),firstKey)).isEqualTo(first);
        assertCode(()->service.advance(a,ra.runId(),new Advance(1L,1L),firstKey),ErrorCode.CONFLICT);
        assertCode(()->service.advance(a,ra.runId(),new Advance(0L,0L),key()),ErrorCode.CONFLICT);
        assertCode(()->service.advance(b,ra.runId(),new Advance(2L,2L),key()),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.timeline(b,ra.runId(),null,null,null),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.events(b,ra.runId(),ORIGIN,ORIGIN.plusSeconds(7200),null),ErrorCode.RESOURCE_NOT_FOUND);
        service.timeline(a,ra.runId(),null,null,null);service.current(a);
        assertThat(facts()).isEqualTo(before);
    }
    @Test void concurrentDuplicateAndCompetingPublicAdvancesOnlyCommitOnce() throws Exception {
        long owner=user();var run=service.start(owner,request(null),key());String key=key();
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Batch> job=()->{barrier.await();return service.advance(owner,run.runId(),new Advance(0L,0L),key);};
            var a=pool.submit(job);var b=pool.submit(job);assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        }
        assertThat(service.current(owner).version()).isEqualTo(1);
        var compete=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Object> job=()->{compete.await();try{return service.advance(owner,run.runId(),new Advance(1L,1L),key());}catch(BusinessException e){return e.errorCode();}};
            var a=pool.submit(job);var b=pool.submit(job);var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertThat(results.stream().filter(Batch.class::isInstance).count()).isEqualTo(1);assertThat(results).contains(ErrorCode.CONFLICT);
        }
        assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",run.runId())).isEqualTo(2);
    }
    @Test void exhaustedCalendarCacheFailureAndOuterRollbackLeaveNoPartialHour() {
        long owner=user();rule(owner,"ONCE","ACTIVE");var run=service.start(owner,request(null),key());String calendar=store.current(owner).calendarSnapshot();
        var tooShort=new SimulationCalendar.Snapshot("short",List.of(new SimulationCalendar.Session("2024-10-29",ORIGIN.minusSeconds(60),ORIGIN.plusSeconds(600))));
        jdbc.update("UPDATE simulation_runs SET calendar_snapshot=? WHERE id=?",encode(tooShort),run.runId());
        assertCode(()->service.advance(owner,run.runId(),new Advance(0L,0L),key()),ErrorCode.SERVICE_UNAVAILABLE);
        jdbc.update("UPDATE simulation_runs SET calendar_snapshot=? WHERE id=?",calendar,run.runId());
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"test cache unavailable")).when(index).candidates(eq(SimulationIsolationService.key(run.runId())),anyString(),any(),any(),any());
        try{assertCode(()->service.advance(owner,run.runId(),new Advance(0L,0L),key()),ErrorCode.SERVICE_UNAVAILABLE);}finally{reset(index);}
        String retryKey=key();
        assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{service.advance(owner,run.runId(),new Advance(0L,0L),retryKey);throw new IllegalStateException("crash before commit");})).hasMessageContaining("crash");
        assertThat(service.current(owner).version()).isZero();assertThat(service.current(owner).simClock()).isEqualTo(ORIGIN);
        assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",run.runId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM simulation_rule_events WHERE run_id=?",run.runId())).isZero();
        assertThat(service.advance(owner,run.runId(),new Advance(0L,0L),retryKey).triggered()).isEqualTo(1);
    }
    @Test void pointsHaveBoundedPagesAndFixedWatermarkAcrossLaterAdvances() {
        long owner=user();var run=service.start(owner,request(null),key());
        for(long i=0;i<11;i++)service.advance(owner,run.runId(),new Advance(i,i),key());
        var first=service.timeline(owner,run.runId(),null,null,null);assertThat(first.simulation()).hasSize(600);assertThat(first.pointCursor()).isNotNull();
        service.advance(owner,run.runId(),new Advance(11L,11L),key());
        var second=service.timeline(owner,run.runId(),first.range().from(),first.range().to(),first.pointCursor());
        assertThat(second.simulation()).hasSize(60);assertThat(second.pointCursor()).isNull();assertThat(second.version()).isEqualTo(11);
        assertThat(second.simulation().getFirst().pointSeq()).isEqualTo(601);assertThat(second.simulation().getLast().pointSeq()).isEqualTo(660);
        assertThat(second.realPrefix()).isEqualTo(first.realPrefix());assertThat(second.simClock()).isEqualTo(first.simClock());
    }
    @Test void eventPagesRetainEveryOwnedFrozenSnapshotAfterRealRuleDeletion() {
        long owner=user(),other=user();for(int i=0;i<105;i++)rule(owner,"ONCE","ACTIVE");rule(other,"ONCE","ACTIVE");
        var run=service.start(owner,request(null),key());service.advance(owner,run.runId(),new Advance(0L,0L),key());
        jdbc.update("UPDATE alert_rules SET name='edited',threshold_price=999,status='DELETED',version=version+1 WHERE user_id=?",owner);
        var before=facts();var first=service.events(owner,run.runId(),ORIGIN,ORIGIN.plusSeconds(3600),null);
        var second=service.events(owner,run.runId(),ORIGIN,ORIGIN.plusSeconds(3600),first.eventCursor());
        assertThat(first.events()).hasSize(100);assertThat(second.events()).hasSize(5);assertThat(second.eventCursor()).isNull();
        assertThat(second.watermark()).isEqualTo(first.watermark());assertThat(first.events()).allSatisfy(e->assertThat(e.ruleSnapshot().path("name").asText()).isEqualTo("Frozen"));
        assertThat(second.events()).allSatisfy(e->assertThat(e.ruleSnapshot().path("thresholdPrice").decimalValue()).isEqualByComparingTo("100.0001"));
        assertThat(service.current(owner).stats().triggered()).isEqualTo(105);assertThat(facts()).isEqualTo(before);
    }
    @Test void failedReplacementPreservesOldResultsAndReplacedIdsCannotBeRevived() {
        long owner=user();var old=service.start(owner,request(null),key());service.advance(owner,old.runId(),new Advance(0L,0L),key());
        String startKey=key();var replacement=request(old.runId());
        assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{service.start(owner,replacement,startKey);throw new IllegalStateException("rollback replacement");})).hasMessageContaining("rollback");
        assertThat(service.current(owner).runId()).isEqualTo(old.runId());assertThat(service.current(owner).version()).isEqualTo(1);
        var fresh=service.start(owner,replacement,startKey);assertThat(fresh.runId()).isNotEqualTo(old.runId());assertThat(fresh.version()).isZero();
        assertCode(()->service.timeline(owner,old.runId(),null,null,null),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.advance(owner,old.runId(),new Advance(1L,1L),key()),ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(()->service.start(owner,replacement,startKey+"x"),ErrorCode.VALIDATION_ERROR);
        assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",old.runId())).isZero();
    }
    @Test void unsupportedGeneratorKeepsSavedResultsReadableAndCannotAdvance() {
        long owner=user();var run=service.start(owner,request(null),key());service.advance(owner,run.runId(),new Advance(0L,0L),key());
        var before=service.timeline(owner,run.runId(),null,null,null);
        jdbc.update("UPDATE simulation_runs SET generator_version='retired-fixture' WHERE id=?",run.runId());
        assertThat(service.current(owner).generatorSupported()).isFalse();
        assertThat(service.timeline(owner,run.runId(),null,null,null)).isEqualTo(before);
        assertCode(()->service.advance(owner,run.runId(),new Advance(1L,1L),key()),ErrorCode.CONFLICT);
        assertThat(service.current(owner).version()).isEqualTo(1);
    }
    @Test void insufficientFrozenPrefixRefusesReplacementWithoutLosingOldRun() {
        long owner=user();var run=service.start(owner,request(null),key());
        jdbc.update("UPDATE market_history_coverage SET requested_from=? WHERE stock_id=?",MarketTime.utc(ORIGIN),stock);
        assertCode(()->service.start(owner,request(run.runId()),key()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(service.current(owner).runId()).isEqualTo(run.runId());
    }
    @Test @Timeout(300)
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="pricepulse.p5.capacity",matches="true")
    void recordsV2LongRunBurstAndReplacementCapacityWithoutFormalSideEffects() throws Exception {
        var zone=MarketTime.NEW_YORK;
        for(LocalDate day=LocalDate.of(2024,11,6);!day.isAfter(LocalDate.of(2024,12,13));day=day.plusDays(1)) {
            if(day.getDayOfWeek()==DayOfWeek.SATURDAY || day.getDayOfWeek()==DayOfWeek.SUNDAY)continue;
            jdbc.update("INSERT INTO market_sessions(provider,session_date,opens_at,closes_at,fetched_at) VALUES('ALPACA',?,?,?,NOW(6))",day,
                    MarketTime.utc(ZonedDateTime.of(day,LocalTime.of(9,30),zone).toInstant()),MarketTime.utc(ZonedDateTime.of(day,LocalTime.of(16,0),zone).toInstant()));
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("fixture","SYNTHETIC_DISPOSABLE_ONLY");report.put("javaRuntime",System.getProperty("java.runtime.version"));
        report.put("architecture",System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("mysqlImage",MYSQL.getDockerImageName());report.put("redisImage",REDIS.getDockerImageName());
        report.put("seed",42);report.put("developmentDatabaseConnected",false);
        var before=facts();long person=user();Current run=service.start(person,request(null),key());
        var batches=new ArrayList<Double>();long wall=System.nanoTime();
        for(long i=0;i<100;i++){long started=System.nanoTime();service.advance(person,run.runId(),new Advance(i,i),key());batches.add(ms(started));}
        double wallMs=ms(wall);var reads=new ArrayList<Double>();
        long started=System.nanoTime();var first=service.timeline(person,run.runId(),null,null,null);reads.add(ms(started));
        int points=first.simulation().size(),pages=1;String cursor=first.pointCursor();
        while(cursor!=null){started=System.nanoTime();var page=service.timeline(person,run.runId(),first.range().from(),first.range().to(),cursor);reads.add(ms(started));points+=page.simulation().size();cursor=page.pointCursor();pages++;}
        assertThat(points).isEqualTo(6000);assertThat(pages).isEqualTo(10);
        started=System.nanoTime();service.start(person,request(run.runId()),key());double longReplacement=ms(started);
        assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",run.runId())).isZero();
        report.put("priceOnly",Map.of("tradingHours",100,"savedPoints",points,"pages",pages,"batchLatency",stats(batches),
                "savedPointsPerSecond",6000_000.0/wallMs,"pageLatency",stats(reads),"replacementMs",longReplacement));
        var dense=new ArrayList<Map<String,Object>>();
        for(int scale:List.of(250,1000)) {
            long owner=user();var rows=new ArrayList<Object[]>();for(int i=0;i<scale;i++)rows.add(new Object[]{owner,stock});
            jdbc.batchUpdate("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,status) VALUES(?,?,'P5 dense synthetic','ABOVE',100.0001,'ONCE','ACTIVE')",rows);
            Current current=service.start(owner,request(null),key());var advances=new ArrayList<Double>();var replacements=new ArrayList<Double>();
            for(int i=0;i<25;i++) {
                started=System.nanoTime();var batch=service.advance(owner,current.runId(),new Advance(0L,0L),key());advances.add(ms(started));
                assertThat(batch.triggered()).isEqualTo(scale);assertThat(batch.points()).hasSize(60);
                assertThat(count("SELECT COUNT(*) FROM simulation_rule_events WHERE run_id=?",current.runId())).isEqualTo(scale);
                if(i==0) {
                    int eventCount=0;String next=null;do{var page=service.events(owner,current.runId(),ORIGIN,batch.toClock(),next);eventCount+=page.events().size();next=page.eventCursor();}while(next!=null);
                    assertThat(eventCount).isEqualTo(scale);
                }
                String old=current.runId();started=System.nanoTime();current=service.start(owner,request(old),key());replacements.add(ms(started));
                assertThat(count("SELECT COUNT(*) FROM simulation_rule_events WHERE run_id=?",old)).isZero();
                assertThat(count("SELECT COUNT(*) FROM simulation_rule_snapshots WHERE run_id=?",old)).isZero();
                assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",old)).isZero();
            }
            dense.add(Map.of("rules",scale,"samples",25,"eventsPerFirstPoint",scale,"batchLatency",stats(advances),
                    "replacementLatency",stats(replacements),"allOldChildrenDeleted",true));
        }
        report.put("simultaneousTriggers",dense);
        // Isolate the wait for the production stock-row locking pattern; 25ms is deliberate contention, not normal traffic.
        var waits=new ArrayList<Double>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int i=0;i<12;i++) {
                var held=new CountDownLatch(1);var release=new CountDownLatch(1);
                var holder=pool.submit(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
                    jdbc.queryForObject("SELECT id FROM stocks WHERE id=? FOR UPDATE",Long.class,stock);held.countDown();
                    try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("lock gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                }));
                assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var attempting=new CountDownLatch(1);
                var waiter=pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{
                    attempting.countDown();long began=System.nanoTime();jdbc.queryForObject("SELECT id FROM stocks WHERE id=? FOR UPDATE",Long.class,stock);return ms(began);
                }));
                try{assertThat(attempting.await(10,TimeUnit.SECONDS)).isTrue();Thread.sleep(25);}finally{release.countDown();}
                waits.add(waiter.get(10,TimeUnit.SECONDS));holder.get(10,TimeUnit.SECONDS);
            }
        }
        report.put("stockRowLock",Map.of("deliberateHoldMs",25,"samples",12,"waitLatency",stats(waits),"interpretation","controlled contention; not uncontended latency or capacity"));
        assertThat(facts()).isEqualTo(before);report.put("formalBusinessFactsChangedBySimulation",0);report.put("status","PASSED");
        var directory=java.nio.file.Path.of(System.getProperty("pricepulse.p5.report-dir","target/v2-p5"));java.nio.file.Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("simulation-capacity.json").toFile(),report);
    }
    private static double ms(long started){return (System.nanoTime()-started)/1_000_000.0;}
    private static Map<String,Object> stats(List<Double> values){var sorted=values.stream().sorted().toList();return Map.of("samples",values.size(),"meanMs",values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),"p95Ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1),"p99Ms",sorted.get((int)Math.ceil(sorted.size()*.99)-1),"maxMs",sorted.getLast(),"samplesMs",values);}
    private Start request(String expected){return new Start(stock,new Origin(OriginKind.HISTORICAL_POINT,"bar:"+bar,"1"),SimulationGenerator.Mode.RISE,new BigDecimal("0.003"),42L,expected);}
    private Start latest(String expected){return new Start(stock,new Origin(OriginKind.LATEST,null,null),SimulationGenerator.Mode.RISE,new BigDecimal("0.003"),42L,expected);}
    private String key(){return UUID.randomUUID().toString();}
    private long user(){String email=key()+"@test.local";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'test','Simulation')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    private void rule(long user,String mode,String status){jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status) VALUES(?,?,'Frozen','ABOVE',100.0001,?,?,?)",user,stock,mode,mode.equals("REPEAT")?new BigDecimal("99.99"):null,status);}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private List<?> facts(){return List.of(jdbc.queryForMap("SELECT latest_price,latest_quote_at,rule_index_token FROM stocks WHERE id=?",stock),count("SELECT COUNT(*) FROM quote_events"),count("SELECT COUNT(*) FROM alert_triggers"),count("SELECT COUNT(*) FROM outbox_events"),count("SELECT COUNT(*) FROM notifications"),count("SELECT COALESCE(SUM(version),0) FROM alert_rules"),count("SELECT COUNT(*) FROM market_price_bars"));}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,ErrorCode code){assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));}
    private String encode(Object object){try{return json.writeValueAsString(object);}catch(Exception e){throw new AssertionError(e);}}
    private String token(long user){Instant now=Instant.now();var claims=JwtClaimsSet.builder().issuer("price-pulse").subject(Long.toString(user)).issuedAt(now).expiresAt(now.plusSeconds(600)).build();return "Bearer "+jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();}
}
