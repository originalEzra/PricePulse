package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.common.error.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@ActiveProfiles("integration")
@Import(RealQuoteTestSupport.class)
@Testcontainers
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class MarketTimelineInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("timeline").withUrlParam("rewriteBatchedStatements","true");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"timeline-test-only-secret-at-least-32-bytes");
    }
    @Autowired QuoteIngestionService ingestion;
    @Autowired MarketDataMapper data;
    @Autowired MarketHistoryCache cache;
    @Autowired MarketTimelineService timeline;
    @Autowired RealQuoteTestSupport.FixtureClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwt;
    @Autowired PlatformTransactionManager transactions;
    static final Instant T=Instant.parse("2026-10-28T13:30:00.123456789Z");

    @Test void exactNanosecondsEqualTimeDistinctTradesAndDuplicatesPreserveOriginalNotificationChain() {
        long user=user(),stock=stock();rule(user,stock,"ACTIVE");
        assertThat(ingest(stock,"first",T,"99","session1").matching().triggered()).isZero();
        var at=T.plusNanos(1);
        var a=ingest(stock,"second",at,"100","session1");
        assertThat(a.matching().triggered()).isEqualTo(1);
        assertThat(ingest(stock,"second",at,"100","session1").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        assertThat(ingest(stock,"same-time-rearm",at,"95","session1").matching().rearmed()).isEqualTo(1);
        assertThat(ingest(stock,"same-time-trigger",at,"100","session1").matching().triggered()).isEqualTo(1);
        assertThat(ingest(stock,"older",T,"105","session1").outcome()).isEqualTo(QuoteOutcome.DISCARDED_OUT_OF_ORDER);
        assertThatThrownBy(()->ingest(stock,"second",at,"102","session1")).isInstanceOf(BusinessException.class);
        var state=data.streamState(stock);
        assertThat(state.eventTimeNs()).isEqualTo(MarketTime.nanos(at));assertThat(state.acceptedSequence()).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers t JOIN quote_events q ON q.id=t.quote_event_id WHERE q.stock_id=?",stock)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE JSON_EXTRACT(payload,'$.stockId')=?",stock)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM quote_events WHERE stock_id=? AND origin_type='REAL' AND event_time_ns IS NOT NULL",stock)).isEqualTo(5);
    }
    @Test void reconnectBaselineRearmsCooldownButNeverCreatesCrossGapAlerts() {
        long owner=user(),stock=stock();long rule=rule(owner,stock,"COOLDOWN");
        var first=ingest(stock,"base",T,"95","connectionA");
        assertThat(first.matching().rearmed()).isEqualTo(1);assertThat(first.matching().triggered()).isZero();
        assertThat(ingest(stock,"trigger",T.plusSeconds(1),"100","connectionA").matching().triggered()).isEqualTo(1);
        assertThat(ingest(stock,"after-gap",T.plusSeconds(20),"95","connectionB").matching().rearmed()).isEqualTo(1);
        assertThat(ingest(stock,"trigger2",T.plusSeconds(21),"100","connectionB").matching().triggered()).isEqualTo(1);
        jdbc.update("UPDATE alert_rules SET status='ACTIVE',version=version+1 WHERE id=?",rule);
        jdbc.update("UPDATE stocks SET rule_index_token=UUID() WHERE id=?",stock);
        assertThat(ingest(stock,"gap2",T.plusSeconds(40),"110","connectionC").matching().triggered()).isZero();
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM quote_events WHERE stock_id=? AND baseline_reason IS NOT NULL",stock)).isEqualTo(3);
    }
    @Test void historyCacheRevisionAndCalendarDoNotChangeRealOrSimulationState() {
        long stock=stock();var before=facts(stock);
        var result=download(stock,"101.0000");
        cache.store(result,List.of(stock),T.minusSeconds(60),T.plusSeconds(300));
        cache.store(result,List.of(stock),T.minusSeconds(60),T.plusSeconds(300));
        assertThat(data.bars(stock,MarketTime.utc(T.minusSeconds(60)),MarketTime.utc(T.plusSeconds(300))).getFirst().revision()).isEqualTo(1);
        cache.store(download(stock,"102.0000"),List.of(stock),T.minusSeconds(60),T.plusSeconds(300));
        assertThat(data.bars(stock,MarketTime.utc(T.minusSeconds(60)),MarketTime.utc(T.plusSeconds(300))).getFirst().revision()).isEqualTo(2);
        assertThat(facts(stock)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT latest_price FROM stocks WHERE id=?",BigDecimal.class,stock)).isNull();
        assertThat(data.streamState(stock)).isNull();
    }
    @Test void cacheWarmupCanCommitWhileLiveMatchingHoldsTheStockRowLock() {
        long stock=stock();
        try(var executor=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).execute(status->{
                jdbc.queryForMap("SELECT id FROM stocks WHERE id=? FOR UPDATE",stock);
                try {
                    executor.submit(()->cache.store(download(stock,"101.0000"),List.of(stock),T.minusSeconds(60),T.plusSeconds(300))).get(3,TimeUnit.SECONDS);
                }catch(Exception e){throw new RuntimeException("Cache writes must not wait for the live stock lock",e);}
                return null;
            });
        }
        assertThat(data.coverage(stock)).isNotNull();assertThat(data.streamState(stock)).isNull();
    }
    @Test void readOnlyTimelinePaginatesAllOwnedEventsRetainsMovedDeletedRuleSnapshotsAndUsesActualEventTime() {
        long owner=user(),outsider=user(),stock=stock(),other=stock();long rule=rule(owner,stock,"ACTIVE");rule(outsider,stock,"ACTIVE");
        ingest(stock,"base",T,"99","one");
        for(int i=0;i<105;i++) {
            ingest(stock,"up-"+i,T.plusSeconds(i*2+1),"100","one");
            ingest(stock,"down-"+i,T.plusSeconds(i*2+2),"95","one");
        }
        cache.store(download(stock,"101.0000"),List.of(stock),T.minusSeconds(60),T.plusSeconds(300));
        jdbc.update("UPDATE alert_rules SET stock_id=?,threshold_price=999,name='edited',status='DELETED',version=version+1 WHERE id=?",other,rule);
        var before=facts(stock);
        var dto=clock.at(LocalDateTime.parse("2026-10-28T14:00:00"),()->timeline.timeline(owner,stock,"1D",null));
        assertThat(dto.events()).hasSize(100);assertThat(dto.eventCursor()).isNotNull();assertThat(dto.thresholds()).isEmpty();
        assertThat(dto.points()).isNotEmpty();assertThat(dto.points().getFirst().eventTime()).isEqualTo(Instant.parse("2026-10-28T13:31:00Z"));
        assertThat(dto.events().getFirst().eventTime()).isEqualTo(T.plusSeconds(1));
        assertThat(dto.events()).allSatisfy(e->{
            assertThat(e.ruleSnapshot().get("thresholdPrice").decimalValue()).isEqualByComparingTo("100");
            assertThat(e.ruleSnapshot().get("name").asText()).isEqualTo("Original");
            assertThat(e.notificationStatus()).isEqualTo("PENDING");
        });
        String cursor=dto.eventCursor();
        var next=timeline.events(owner,stock,dto.range().from(),dto.range().asOf(),cursor);
        assertThat(next.events()).hasSize(5);assertThat(next.eventCursor()).isNull();
        assertThat(next.watermark()).isEqualTo(dto.watermark());
        assertThatThrownBy(()->timeline.events(outsider,stock,dto.range().from(),dto.range().asOf(),cursor)).isInstanceOf(BusinessException.class);
        for(String window:List.of("1H","1D","1W","1M"))clock.at(LocalDateTime.parse("2026-10-28T14:00:00"),()->timeline.timeline(owner,stock,window,null));
        assertThat(facts(stock)).isEqualTo(before);
        assertThat(timeline.events(owner,other,dto.range().from(),dto.range().asOf(),null).events()).isEmpty();
    }
    @Test void authenticatedApiDoesNotAllowClientQuotesAndReturnsUtcStringPrices()throws Exception {
        long owner=user(),stock=stock();cache.store(download(stock,"101.0000"),List.of(stock),T.minusSeconds(60),T.plusSeconds(300));
        mvc.perform(get("/api/market/stocks/{id}/timeline",stock)).andExpect(status().isUnauthorized());
        String auth="Bearer "+token(owner);
        clock.at(LocalDateTime.parse("2026-10-28T14:00:00"),()->{try {
        mvc.perform(get("/api/market/stocks/{id}/timeline",stock).param("anchor","2026-10-28T14:00:00Z").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.scope").value("REAL"))
            .andExpect(jsonPath("$.data.points[0].price").value("101.0000"))
            .andExpect(jsonPath("$.data.points[0].eventTime").value("2026-10-28T13:31:00Z"))
            .andExpect(jsonPath("$.data.sessions[?(@.date == '2026-10-28')].open").value(org.hamcrest.Matchers.hasItem("2026-10-28T13:30:00Z")))
            .andExpect(jsonPath("$.data.dataQuality.barSamplingApplied").value(false))
            .andExpect(jsonPath("$.data.health.businessWritesEnabled").value(false));
        }catch(Exception e){throw new RuntimeException(e);}return null;});
        mvc.perform(post("/api/market/quotes").header("Authorization",auth).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void monthAndWeekReturnEveryCachedMinuteAndExposeIntradayHolesWithoutChangingEventsOrBusinessState() {
        long owner=user(),stock=stock();
        var calendar=new ArrayList<Session>();var bars=new ArrayList<Bar>();
        Instant end=Instant.parse("2026-10-28T20:00:00Z"),begin=Instant.parse("2026-09-25T13:30:00Z");
        for(LocalDate day=LocalDate.parse("2026-09-25");!day.isAfter(LocalDate.parse("2026-11-02"));day=day.plusDays(1)) {
            if(day.getDayOfWeek()==DayOfWeek.SATURDAY || day.getDayOfWeek()==DayOfWeek.SUNDAY)continue;
            var session=new Session(day,day.atTime(13,30),day.atTime(20,0));calendar.add(session);
            if(session.open().isAfter(end))continue;
            for(int i=0;i<390;i++) {
                if(day.equals(LocalDate.parse("2026-10-26")) && i>=120 && i<125)continue;
                var start=session.opensAt().plusMinutes(i);
                bars.add(new Bar(null,stock,start,start.plusMinutes(1),new BigDecimal("100"),new BigDecimal("101"),new BigDecimal("99"),new BigDecimal("100.0001"),1,MarketTime.utc(end)));
            }
        }
        cache.store(new AlpacaDataClient.Download(calendar,bars,2),List.of(stock),begin,end);
        var before=facts(stock);
        for(String window:List.of("1H","1D","1W","1M")) {
            var dto=clock.at(LocalDateTime.ofInstant(end,ZoneOffset.UTC),()->timeline.timeline(owner,stock,window,null));
            var expected=data.bars(stock,MarketTime.utc(dto.range().from()),MarketTime.utc(dto.range().asOf()));
            assertThat(dto.points()).extracting(Point::pointRef).containsExactlyElementsOf(expected.stream().map(b->"bar:"+b.id()).toList());
            assertThat(dto.dataQuality().availableBars()).isEqualTo(expected.size());
            assertThat(dto.dataQuality().displayedBars()).isEqualTo(expected.size());
            assertThat(dto.dataQuality().barSamplingApplied()).isFalse();
            assertThat(dto.dataQuality().historyCoverageComplete()).isTrue();
            assertThat(dto.dataQuality().unavailableMinutes()).isEqualTo(Set.of("1W","1M").contains(window)?5:0);
            if(window.equals("1M"))assertThat(dto.points().size()).isGreaterThan(8000);
            assertThat(dto.events()).isEmpty();
            assertThat(facts(stock)).isEqualTo(before);
        }
    }
    @Test void staleAndFutureMessagesArePersistedAsDiscardedWithoutAdvancingWatermark() {
        long stock=stock();ingest(stock,"first",T,"99","one");
        var before=data.streamState(stock);
        var stale=new QuoteCandidate("stale-"+stock,stock,new BigDecimal("100"),LocalDateTime.ofInstant(T.minusSeconds(121),ZoneOffset.UTC),"ALPACA_IEX",null,T,"ALPACA","iex","one",null);
        assertThat(clock.at(LocalDateTime.ofInstant(T,ZoneOffset.UTC),()->ingestion.ingest(stale,QuoteIngestionPolicy.LIVE)).outcome()).isEqualTo(QuoteOutcome.DISCARDED_STALE);
        var future=new QuoteCandidate("future-"+stock,stock,new BigDecimal("100"),LocalDateTime.ofInstant(T.plusSeconds(31),ZoneOffset.UTC),"ALPACA_IEX",null,T,"ALPACA","iex","one",null);
        assertThat(clock.at(LocalDateTime.ofInstant(T,ZoneOffset.UTC),()->ingestion.ingest(future,QuoteIngestionPolicy.LIVE)).outcome()).isEqualTo(QuoteOutcome.DISCARDED_FUTURE);
        assertThat(data.streamState(stock)).isEqualTo(before);
    }
    private QuoteResult ingest(long stock,String key,Instant at,String price,String continuity) {
        return clock.at(LocalDateTime.ofInstant(at,ZoneOffset.UTC),()->ingestion.ingest(new QuoteCandidate(key+":"+stock,stock,new BigDecimal(price),
                LocalDateTime.ofInstant(at,ZoneOffset.UTC),"ALPACA_IEX",null,at,"ALPACA","iex",continuity,"RECONNECT"),QuoteIngestionPolicy.LIVE));
    }
    private AlpacaDataClient.Download download(long stock,String close) {
        var session=new Session(LocalDate.parse("2026-10-28"),LocalDateTime.parse("2026-10-28T13:30:00"),LocalDateTime.parse("2026-10-28T20:00:00"));
        List<Bar> bars=new ArrayList<>();
        for(int i=0;i<4;i++) {
            var start=session.opensAt().plusMinutes(i);
            bars.add(new Bar(null,stock,start,start.plusMinutes(1),new BigDecimal("100"),new BigDecimal("103"),new BigDecimal("99"),new BigDecimal(close),1,MarketTime.utc(T.plusSeconds(300))));
        }
        return new AlpacaDataClient.Download(List.of(session),bars,1);
    }
    private long user(){String email=UUID.randomUUID()+"@test.local";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'test','Timeline')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    private long stock(){String symbol="T"+UUID.randomUUID().toString().substring(0,15);jdbc.update("INSERT INTO stocks(symbol,name,market,currency) VALUES(?,'Timeline','US','USD')",symbol);return jdbc.queryForObject("SELECT id FROM stocks WHERE symbol=?",Long.class,symbol);}
    private long rule(long user,long stock,String status){jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status) VALUES(?,?,'Original','ABOVE',100,'REPEAT',95,?)",user,stock,status);return jdbc.queryForObject("SELECT MAX(id) FROM alert_rules WHERE user_id=?",Long.class,user);}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private List<?> facts(long stock){return List.of(jdbc.queryForMap("SELECT latest_price,latest_quote_at,rule_index_token FROM stocks WHERE id=?",stock),
        count("SELECT COUNT(*) FROM quote_events"),count("SELECT COUNT(*) FROM alert_triggers"),count("SELECT COUNT(*) FROM outbox_events"),count("SELECT COUNT(*) FROM notifications"),
        count("SELECT COUNT(*) FROM simulation_runs"),count("SELECT COALESCE(SUM(version),0) FROM alert_rules"));}
    private String token(long user){Instant now=Instant.now();var claims=JwtClaimsSet.builder().issuer("price-pulse").subject(Long.toString(user)).issuedAt(now).expiresAt(now.plusSeconds(600)).build();return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();}
}
