package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.simulation.SimulationApi.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties={"price-pulse.alpaca.enabled=false","price-pulse.messaging.enabled=false"})
@ActiveProfiles("integration") @Import(RealQuoteTestSupport.class) @Testcontainers
class SimulationOpeningGapInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("simulated_opening_gap");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"isolated-opening-gap-test-key-at-least-32-bytes");
    }
    @Autowired SimulationService service;@Autowired SimulationMapper store;@Autowired MarketHistoryCache cache;
    @Autowired JdbcTemplate jdbc;@Autowired RealQuoteTestSupport.FixtureClock clock;@Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    long stock,closeBar;static final Instant CLOSE=Instant.parse("2024-11-27T21:00:00Z"),OPEN=Instant.parse("2024-11-29T14:30:00Z");
    @BeforeEach void seed(){
        stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='AAPL'",Long.class);
        jdbc.update("DELETE FROM market_price_bars");jdbc.update("DELETE FROM market_sessions");jdbc.update("DELETE FROM market_stream_state");
        var sessions=new ArrayList<Session>();var bars=new ArrayList<Bar>();
        for(String date:List.of("2024-11-26","2024-11-27","2024-11-29","2024-12-02","2024-12-03")){
            var day=LocalDate.parse(date);Instant open=ZonedDateTime.of(day,LocalTime.of(9,30),MarketTime.NEW_YORK).toInstant();
            Instant close=ZonedDateTime.of(day,date.equals("2024-11-29")?LocalTime.of(13,0):LocalTime.of(16,0),MarketTime.NEW_YORK).toInstant();
            sessions.add(new Session(day,MarketTime.utc(open),MarketTime.utc(close)));
            if(day.isAfter(LocalDate.of(2024,11,27)))continue;
            for(Instant at=open;at.isBefore(close);at=at.plusSeconds(60)){BigDecimal price=new BigDecimal("345");bars.add(new Bar(null,stock,MarketTime.utc(at),MarketTime.utc(at.plusSeconds(60)),price,price,price,price,1,MarketTime.utc(CLOSE.plusSeconds(60))));}
        }
        cache.store(new AlpacaDataClient.Download(sessions,bars,1),List.of(stock),sessions.getFirst().open(),CLOSE);
        closeBar=jdbc.queryForObject("SELECT id FROM market_price_bars WHERE stock_id=? AND interval_end=?",Long.class,stock,MarketTime.utc(CLOSE));
    }
    @Test void upwardGapTriggersOnceAtActualOpeningAndHasSimulationContext(){
        jdbc.update("DELETE FROM market_price_bars");jdbc.update("DELETE FROM market_history_coverage"); // A custom simulation needs calendar, not real price history.
        long u=user();rule(u,"ABOVE","350","ONCE",null,"COMPLETED");var before=facts();var run=start(u,custom("345","355",SimulationGenerator.Mode.NORMAL,null));
        assertThat(run.stats().triggered()).isZero();assertThat(run.origin().path("provider").asText()).isEqualTo("SIMULATION");assertThat(service.timeline(u,run.runId(),null,null,null).realPrefix()).isEmpty();
        var batch=service.advance(u,run.runId(),new Advance(0L,0L),key());assertThat(batch.points()).hasSize(1);assertThat(batch.points().getFirst().price()).isEqualTo("355.0000");assertThat(batch.toClock()).isEqualTo(OPEN);assertThat(batch.triggered()).isEqualTo(1);
        var event=service.events(u,run.runId(),CLOSE,OPEN,null).events().getFirst();assertThat(event.eventTime()).isEqualTo(OPEN);assertThat(event.triggerContext().path("referencePrice").asText()).isEqualTo("345.0000");assertThat(event.triggerContext().path("kind").asText()).isEqualTo("SIMULATED_OPENING_GAP");
        assertThat(service.current(u).ruleSnapshots().getFirst().currentState().name()).isEqualTo("COMPLETED");assertThat(facts()).isEqualTo(before);
    }
    @Test void downwardGapAndNonCrossingGapUseTheSameDirectionalBoundaries(){
        long down=user();rule(down,"BELOW","340","ONCE",null,"ACTIVE");var run=start(down,custom("345","335",SimulationGenerator.Mode.NORMAL,null));
        assertThat(service.advance(down,run.runId(),new Advance(0L,0L),key()).triggered()).isEqualTo(1);
        long no=user();rule(no,"ABOVE","350","ONCE",null,"ACTIVE");var quiet=start(no,custom("345","348",SimulationGenerator.Mode.NORMAL,null));
        assertThat(service.advance(no,quiet.runId(),new Advance(0L,0L),key()).triggered()).isZero();
        long flat=user();rule(flat,"ABOVE","350","ONCE",null,"ACTIVE");var equal=start(flat,custom("345","345",SimulationGenerator.Mode.NORMAL,null));assertThat(service.advance(flat,equal.runId(),new Advance(0L,0L),key()).triggered()).isZero();
    }
    @Test void historicalReferenceMustBeActualClosingMinuteAndCannotBeOverridden(){
        long u=user();var request=new Start(stock,new Origin(OriginKind.HISTORICAL_POINT,"bar:"+closeBar,"1"),SimulationGenerator.Mode.OPENING_GAP,new BigDecimal("0.003"),42L,null,new OpeningGap(ReferenceSource.REAL_HISTORY,null,new BigDecimal("355"),SimulationGenerator.Mode.NORMAL));
        var run=start(u,request);assertThat(run.origin().path("provider").asText()).isEqualTo("ALPACA");assertThat(service.timeline(u,run.runId(),null,null,null).realPrefix()).hasSize(780);
        assertThat(service.advance(u,run.runId(),new Advance(0L,0L),key()).toClock()).isEqualTo(OPEN);
        var bad=new Start(stock,new Origin(OriginKind.HISTORICAL_POINT,"bar:"+(closeBar-1),"1"),request.mode(),request.stepRatio(),42L,run.runId(),request.openingGap());assertThatThrownBy(()->start(u,bad)).isInstanceOf(BusinessException.class);
        var forged=new Start(stock,request.origin(),request.mode(),request.stepRatio(),42L,run.runId(),new OpeningGap(ReferenceSource.REAL_HISTORY,new BigDecimal("1"),new BigDecimal("355"),SimulationGenerator.Mode.NORMAL));assertThatThrownBy(()->start(u,forged)).isInstanceOf(BusinessException.class);
        assertThat(service.current(u).runId()).isEqualTo(run.runId());
    }
    @Test void openingBatchIsIdempotentAndConcurrentRequestsCannotDuplicateThePoint()throws Exception{
        long u=user();rule(u,"ABOVE","350","ONCE",null,"ACTIVE");var run=start(u,custom("345","355",SimulationGenerator.Mode.NORMAL,null));String id=key();var first=service.advance(u,run.runId(),new Advance(0L,0L),id);
        assertThat(service.advance(u,run.runId(),new Advance(0L,0L),id)).isEqualTo(first);assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",run.runId())).isEqualTo(1);
        long other=user();var racing=start(other,custom("345","355",SimulationGenerator.Mode.NORMAL,null));var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<String> task=()->{gate.await();try{service.advance(other,racing.runId(),new Advance(0L,0L),key());return "OK";}catch(BusinessException e){return "CONFLICT";}};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","CONFLICT");
        }
        assertThat(count("SELECT COUNT(*) FROM simulation_steps WHERE run_id=?",racing.runId())).isEqualTo(1);assertThat(service.current(other).nextPointSeq()).isEqualTo(2);
    }
    @Test void repeatRuleCoolsDownThenRearmsWithoutSamePointDoubleTrigger(){
        long u=user();rule(u,"ABOVE","350","REPEAT","349","COOLDOWN");rule(u,"ABOVE","350","ONCE",null,"PAUSED");var before=facts();var run=start(u,custom("345","355",SimulationGenerator.Mode.FALL,null));
        var opening=service.advance(u,run.runId(),new Advance(0L,0L),key());assertThat(opening.triggered()).isEqualTo(1);
        assertThat(service.current(u).ruleSnapshots().getFirst().currentState().name()).isEqualTo("COOLDOWN");
        var hour=service.advance(u,run.runId(),new Advance(1L,1L),key());assertThat(hour.points()).hasSize(60);assertThat(hour.fromClock()).isEqualTo(OPEN);assertThat(hour.toClock()).isEqualTo(OPEN.plusSeconds(3600));assertThat(hour.triggered()).isZero();assertThat(hour.rearmed()).isEqualTo(1);
        assertThat(service.current(u).ruleSnapshots().getFirst().currentState().name()).isEqualTo("ACTIVE");assertThat(count("SELECT COUNT(*) FROM (SELECT snapshot_rule_id,point_seq FROM simulation_rule_events WHERE run_id=? GROUP BY snapshot_rule_id,point_seq HAVING COUNT(*)>1) x",run.runId())).isZero();assertThat(facts()).isEqualTo(before);
    }
    @Test void openingPlusTenHoursPagesWithoutLosingPointsAndOldOpeningRetryStaysIdempotent(){
        long u=user();rule(u,"ABOVE","350","ONCE",null,"ACTIVE");var before=facts();var run=start(u,custom("345","355",SimulationGenerator.Mode.FALL,null));String id=key();
        var opening=service.advance(u,run.runId(),new Advance(0L,0L),id);
        for(long batch=1;batch<=10;batch++)service.advance(u,run.runId(),new Advance(batch,batch),key());
        var first=service.timeline(u,run.runId(),null,null,null);assertThat(first.simulation()).hasSize(541);assertThat(first.pointCursor()).isNotNull();
        var second=service.timeline(u,run.runId(),first.range().from(),first.range().to(),first.pointCursor());assertThat(second.simulation()).hasSize(60);assertThat(second.pointCursor()).isNull();
        var points=new ArrayList<>(first.simulation());points.addAll(second.simulation());
        assertThat(points).hasSize(601);assertThat(points.stream().map(p->p.pointSeq()).toList()).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,601).boxed().toList());
        assertThat(points.stream().filter(p->p.eventTime().equals(OPEN)).count()).isEqualTo(1);assertThat(points.getLast().eventTime()).isEqualTo(Instant.parse("2024-12-02T21:00:00Z"));
        assertThat(service.advance(u,run.runId(),new Advance(0L,0L),id)).isEqualTo(opening);assertThat(service.current(u).lastBatchNo()).isEqualTo(11);
        assertThat(count("SELECT COUNT(*) FROM simulation_rule_events WHERE run_id=?",run.runId())).isEqualTo(1);assertThat(facts()).isEqualTo(before);
    }
    @Test void earlyCloseWeekendAndFrozenCalendarRemainAuthoritative(){
        long u=user();var run=clock.at(LocalDateTime.parse("2024-11-29T19:00:00"),()->service.start(u,custom("345","355",SimulationGenerator.Mode.RISE,null),key()));
        assertThat(run.simClock()).isEqualTo(Instant.parse("2024-11-29T18:00:00Z"));jdbc.update("DELETE FROM market_sessions");
        var opening=service.advance(u,run.runId(),new Advance(0L,0L),key());assertThat(opening.toClock()).isEqualTo(Instant.parse("2024-12-02T14:30:00Z"));
        assertThat(service.advance(u,run.runId(),new Advance(1L,1L),key()).toClock()).isEqualTo(Instant.parse("2024-12-02T15:30:00Z"));
    }
    @Test void invalidConfigAndUnavailableFutureCalendarPreserveSavedRun(){
        long u=user();var run=start(u,custom("345","355",SimulationGenerator.Mode.NORMAL,null));
        assertThatThrownBy(()->start(u,custom("0","355",SimulationGenerator.Mode.NORMAL,run.runId()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->start(u,custom("345","355",SimulationGenerator.Mode.OPENING_GAP,run.runId()))).isInstanceOf(BusinessException.class);
        var illegal=new Start(stock,new Origin(OriginKind.SIMULATION_REFERENCE,null,null),SimulationGenerator.Mode.NORMAL,new BigDecimal("0.003"),42L,run.runId());assertThatThrownBy(()->start(u,illegal)).isInstanceOf(BusinessException.class);
        jdbc.update("DELETE FROM market_sessions WHERE session_date>'2024-11-27'");assertThatThrownBy(()->start(u,custom("345","355",SimulationGenerator.Mode.NORMAL,run.runId()))).isInstanceOf(BusinessException.class);assertThat(service.current(u).runId()).isEqualTo(run.runId());
    }
    @Test void newFieldsDoNotChangeOldStartHashAndGapStartRetryFreezesInputs(){
        var normal=new Start(stock,new Origin(OriginKind.HISTORICAL_POINT,"bar:"+closeBar,"1"),SimulationGenerator.Mode.NORMAL,new BigDecimal("0.003"),42L,null);
        assertThat(json.valueToTree(normal).has("openingGap")).isFalse();long u=user();String id=key();var req=custom("345","355",SimulationGenerator.Mode.NORMAL,null);
        var run=at(()->service.start(u,req,id));jdbc.update("DELETE FROM market_sessions");assertThat(at(()->service.start(u,req,id)).runId()).isEqualTo(run.runId());
        var changed=custom("345","356",SimulationGenerator.Mode.NORMAL,null);assertThatThrownBy(()->at(()->service.start(u,changed,id))).isInstanceOf(BusinessException.class);
        assertThat(SimulationGenerator.supports(run.generatorVersion())).isTrue();assertThat(run.generatorVersion()).isNotEqualTo(SimulationGenerator.VERSION);
    }
    Start custom(String reference,String opening,SimulationGenerator.Mode next,String expected){return new Start(stock,new Origin(OriginKind.SIMULATION_REFERENCE,null,null),SimulationGenerator.Mode.OPENING_GAP,new BigDecimal("0.003"),42L,expected,new OpeningGap(ReferenceSource.CUSTOM,new BigDecimal(reference),new BigDecimal(opening),next));}
    Current start(long user,Start request){return at(()->service.start(user,request,key()));}
    <T>T at(Supplier<T> task){return clock.at(MarketTime.utc(CLOSE.plusSeconds(3600)),task);}
    String key(){return UUID.randomUUID().toString();}
    long user(){String email=key()+"@gap.fixture";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'test','Gap')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    void rule(long user,String direction,String threshold,String mode,String rearm,String status){jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status) VALUES(?,?,'Gap snapshot',?,?,?,?,?)",user,stock,direction,new BigDecimal(threshold),mode,rearm==null?null:new BigDecimal(rearm),status);}
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    List<?> facts(){return List.of(jdbc.queryForList("SELECT id,latest_price,latest_quote_at,rule_index_token FROM stocks ORDER BY id"),jdbc.queryForList("SELECT id,status,version,effective_at FROM alert_rules ORDER BY id"),count("SELECT COUNT(*) FROM quote_events"),count("SELECT COUNT(*) FROM alert_triggers"),count("SELECT COUNT(*) FROM outbox_events"),count("SELECT COUNT(*) FROM notifications"));}
}
