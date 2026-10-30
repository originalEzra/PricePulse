package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.market.*;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.monitoring.MonitoringModels.*;
import com.ezra.pricepulse.monitoring.*;
import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
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
@Import({RealQuoteTestSupport.class,MonitoringInfrastructureIT.Fixtures.class})
@Testcontainers
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class MonitoringInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("monitoring_it");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"monitoring-only-test-key-at-least-32-bytes");
        r.add("price-pulse.monitoring.enabled",()->true);r.add("price-pulse.monitoring.reconcile-ms",()->3600000);
        r.add("price-pulse.alpaca.enabled",()->false);r.add("price-pulse.messaging.enabled",()->false);
    }
    static final Map<String,String> IDS=Map.of("AAPL","11111111-1111-4111-8111-111111111111","MSFT","22222222-2222-4222-8222-222222222222",
            "PLTR","33333333-3333-4333-8333-333333333333","SPY","44444444-4444-4444-8444-444444444444","LONG","55555555-5555-4555-8555-555555555555");
    static class FixtureTransport implements SubscriptionTransport {
        volatile String state="CONFIRMED",epoch="connection-one";
        public boolean supports(String symbol){return !symbol.equals("LONG");}
        public Observation observe(String symbol){return supports(symbol)?new Observation(state,state.equals("CONFIRMED")?epoch:null,state.equals("FAILED")?"UPSTREAM_REJECTED":null):new Observation("FAILED",null,"DYNAMIC_SUBSCRIPTION_UNAVAILABLE");}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean @Primary FixtureTransport fixtureTransport(){return new FixtureTransport();}
        @Bean @Primary AlpacaAssetClient fixtureAssets(ObjectMapper json,java.time.Clock clock){
            return new AlpacaAssetClient(path->{
                var array=json.createArrayNode();IDS.forEach((symbol,id)->array.add(json.createObjectNode().put("id",id).put("symbol",symbol)
                    .put("name",symbol.equals("LONG")?"Long name ".repeat(25):symbol.equals("PLTR")?"Palantir Technologies":symbol.equals("SPY")?"SPDR ETF Trust":symbol)
                    .put("class","us_equity").put("exchange","NASDAQ").put("status","active").put("tradable",true)));
                if(path.startsWith("/v2/assets?"))return array;
                for(JsonNode asset:array)if(path.endsWith(asset.get("id").asText()))return asset;
                throw new IllegalArgumentException("fixture not found");
            },clock);
        }
    }
    @Autowired MonitoringService service;
    @Autowired MonitoringMapper store;
    @Autowired AssetCatalogService assets;
    @Autowired FixtureTransport upstream;
    @Autowired AlertRuleService ruleService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwt;
    @Autowired ObjectMapper json;
    @Autowired QuoteIngestionService ingestion;
    @Autowired RealQuoteTestSupport.FixtureClock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RuleIndex index;
    @Autowired StockMapper stocks;

    @BeforeEach void reset(){
        jdbc.update("DELETE FROM user_stock_subscriptions");
        jdbc.update("UPDATE market_subscription_control SET symbol_limit=15 WHERE id=1");
        jdbc.update("UPDATE market_subscription_targets t JOIN stocks s ON s.id=t.stock_id SET legacy_hold=(s.symbol IN ('AAPL','MSFT','NVDA','AMZN','GOOGL','ORCL','SMCI','SOFI','AMD','TSLA','CRWD','MU','INTC','BA','HOOD')),slot_reserved=(s.symbol IN ('AAPL','MSFT','NVDA','AMZN','GOOGL','ORCL','SMCI','SOFI','AMD','TSLA','CRWD','MU','INTC','BA','HOOD')),t.state='PENDING',confirmed_epoch=NULL,error_code=NULL,t.version=t.version+1");
        upstream.state="CONFIRMED";upstream.epoch="connection-one";
    }
    @Test void directorySearchFindsOutsideLegacyAssetsLongNamesAndHonestProviderClass() {
        long u=user();var byName=assets.search(u,"Palantir",20);assertThat(byName.assets()).extracting(a->a.asset().symbol()).containsExactly("PLTR");
        assertThat(assets.search(u,"SPY",20).assets().getFirst().asset().assetClass()).isEqualTo("us_equity");
        var longName=assets.search(u,"LONG",20).assets().getFirst();assertThat(longName.asset().name().length()).isGreaterThan(128);assertThat(longName.requestSupported()).isFalse();
        assertThat(assets.search(u,"NO_SUCH_SYMBOL",20).assets()).isEmpty();
        assertThat(assets.search(u,"%",20).assets()).isEmpty();
    }
    @Test void twoUsersShareOneSlotAndCancelOnlyTheirOwnUnfilteredRuleSet() {
        long a=user(),b=user();var first=add(a,"AAPL");var second=add(b,"AAPL");long stock=first.stock().id();
        assertThat(second.state()).isEqualTo("CONFIRMED");assertThat(store.reserved()).isEqualTo(15);assertThat(store.demand(stock)).isEqualTo(2);
        assertThatThrownBy(()->add(a,"AAPL")).isInstanceOf(BusinessException.class);
        var other=rule(b,stock,"Other",new BigDecimal("110"));
        for(int n=0;n<130;n++)jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status) VALUES(?,?,'Many','ABOVE',100,'REPEAT',95,?)",a,stock,List.of("ACTIVE","COOLDOWN","PAUSED","COMPLETED").get(n%4));
        var preview=service.cancellation(a,stock);assertThat(preview.ruleCount()).isEqualTo(130);
        var outcome=service.cancel(a,stock,new Cancel(preview.version(),preview.rulesDigest(),true));
        assertThat(outcome.deletedRules()).isEqualTo(130);assertThat(outcome.globalDemandRemaining()).isTrue();assertThat(outcome.legacyHeld()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM alert_rules WHERE user_id=? AND status='DELETED'",a)).isEqualTo(130);
        assertThat(ruleService.get(b,other.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(store.demand(stock)).isEqualTo(1);assertThat(service.list(a).subscriptions()).isEmpty();
        assertThat(service.list(b).subscriptions()).hasSize(1);assertThat(store.getTarget(stock).slotReserved()).isTrue();
        var last=service.cancellation(b,stock);service.cancel(b,stock,new Cancel(last.version(),last.rulesDigest(),true));
        assertThat(store.demand(stock)).isZero();assertThat(store.getTarget(stock).slotReserved()).isTrue(); // Stage 1 legacy hold, no upstream unsubscribe.
    }
    @Test void cancelRetainsActualTriggerQuoteOutboxAndNotificationFacts() {
        long owner=user();long stock=add(owner,"AAPL").stock().id();var rule=rule(owner,stock,"Once",new BigDecimal("100"));
        Instant at=Instant.parse("2026-10-28T14:00:00Z");
        clock.at(LocalDateTime.ofInstant(at,ZoneOffset.UTC),()->{
            ingestion.ingest(candidate(stock,"99",at),QuoteIngestionPolicy.LIVE);
            ingestion.ingest(candidate(stock,"101",at.plusSeconds(1)),QuoteIngestionPolicy.LIVE);return null;
        });
        long trigger=jdbc.queryForObject("SELECT id FROM alert_triggers WHERE rule_id=?",Long.class,rule.id());
        jdbc.update("INSERT INTO notifications(user_id,trigger_id,title,content) VALUES(?,?,'Kept','Kept')",owner,trigger);
        var before=facts(stock);var preview=service.cancellation(owner,stock);service.cancel(owner,stock,new Cancel(preview.version(),preview.rulesDigest(),true));
        assertThat(facts(stock)).isEqualTo(before);assertThat(count("SELECT COUNT(*) FROM alert_rules WHERE id=? AND status='DELETED'",rule.id())).isEqualTo(1);
    }
    @Test void changedRulesRequireNewConfirmationAndFailedTransactionRestoresSubscriptionRulesAndRedisLookup() {
        long u=user();long stock=add(u,"MSFT").stock().id();var rule=rule(u,stock,"Before",new BigDecimal("100"));
        var old=service.cancellation(u,stock);rule(u,stock,"After",new BigDecimal("110"));
        assertThatThrownBy(()->service.cancel(u,stock,new Cancel(old.version(),old.rulesDigest(),true))).isInstanceOf(BusinessException.class).hasMessageContaining("重新确认");
        var current=service.cancellation(u,stock);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{service.cancel(u,stock,new Cancel(current.version(),current.rulesDigest(),true));throw new IllegalStateException("fixture rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(store.subscription(u,stock).desired()).isTrue();assertThat(ruleService.get(u,rule.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
        var ids=new TransactionTemplate(transactions).execute(status->{var locked=stocks.findActiveByIdForUpdate(stock);return index.candidates(locked,new BigDecimal("99"),new BigDecimal("101"));});
        assertThat(ids).contains(rule.id());
    }
    @Test void oldConfirmedRowsCannotSurviveLostConnectionAndCannotEnableRulesUntilConfirmed() {
        long u=user();var entry=add(u,"AAPL");long stock=entry.stock().id();assertThat(store.getTarget(stock).confirmedEpoch()).isEqualTo("connection-one");
        upstream.state="PENDING";upstream.epoch="connection-two";
        assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("PENDING");
        assertThatThrownBy(()->rule(u,stock,"No fake monitoring",new BigDecimal("100"))).isInstanceOf(BusinessException.class);
        service.reconcile();assertThat(store.getTarget(stock).confirmedEpoch()).isNull();
        upstream.state="CONFIRMED";service.reconcile();assertThat(store.getTarget(stock).confirmedEpoch()).isEqualTo("connection-two");
        assertThat(rule(u,stock,"Recovered",new BigDecimal("100")).status()).isEqualTo(AlertRuleStatus.ACTIVE);
    }
    @Test void budgetIsAtomicAcrossDistinctStockRequestsAndFailedRequestDoesNotClaimMonitoring() throws Exception {
        freeLegacyBudget();jdbc.update("UPDATE market_subscription_control SET symbol_limit=1 WHERE id=1");upstream.state="PENDING";
        long a=user(),b=user();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var x=pool.submit(()->{gate.await();return tryAdd(a,"PLTR");});var y=pool.submit(()->{gate.await();return tryAdd(b,"SPY");});gate.countDown();
            assertThat(List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("PENDING","MONITORING_LIMIT_REACHED");
        }
        assertThat(store.reserved()).isEqualTo(1);
        freeLegacyBudget();jdbc.update("UPDATE market_subscription_targets SET slot_reserved=FALSE,state='FAILED' WHERE legacy_hold=FALSE");
        var failed=add(user(),"LONG");assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("DYNAMIC_SUBSCRIPTION_UNAVAILABLE");
        assertThat(store.getTarget(failed.stock().id()).slotReserved()).isFalse();
    }
    @Test void authenticatedApiKeepsUsersSeparateAndRequiresExplicitCancelConsent()throws Exception {
        long a=user(),b=user();var row=add(a,"AAPL");
        mvc.perform(get("/api/monitoring/subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitoring/subscriptions").header("Authorization",token(b))).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscriptions.length()").value(0));
        mvc.perform(get("/api/monitoring/subscriptions/{id}/cancellation",row.stock().id()).header("Authorization",token(b))).andExpect(status().isNotFound());
        var preview=service.cancellation(a,row.stock().id());
        mvc.perform(post("/api/monitoring/subscriptions/{id}/cancel",row.stock().id()).header("Authorization",token(a)).contentType("application/json")
                .content(json.writeValueAsString(new Cancel(preview.version(),preview.rulesDigest(),false)))).andExpect(status().isBadRequest());
        assertThat(store.subscription(a,row.stock().id()).desired()).isTrue();
        mvc.perform(post("/api/monitoring/subscriptions").header("Authorization",token(a)).contentType("application/json").content(json.writeValueAsString(new Add(IDS.get("AAPL"))))).andExpect(status().isConflict());
    }
    @Test void noDataStaysNullAndHistoricalDisplayNeverMovesFormalWatermark() {
        freeLegacyBudget();long u=user();var entry=add(u,"PLTR");long stock=entry.stock().id();assertThat(entry.stock().latestPrice()).isNull();
        jdbc.update("INSERT INTO market_price_bars(stock_id,provider,feed,resolution,adjustment,interval_start,interval_end,open_price,high_price,low_price,close_price,revision,fetched_at) VALUES(?,'ALPACA','iex','1Min','raw','2026-10-28 19:59:00','2026-10-28 20:00:00',345,345,345,345,1,'2026-10-28 20:01:00')",stock);
        var listed=service.list(u).subscriptions().getFirst();assertThat(listed.stock().latestPrice()).isEqualTo("345.0000");assertThat(listed.stock().priceKind()).isEqualTo("HISTORICAL_BAR_CLOSE");
        assertThat(jdbc.queryForObject("SELECT latest_price FROM stocks WHERE id=?",BigDecimal.class,stock)).isNull();assertThat(count("SELECT COUNT(*) FROM market_stream_state WHERE stock_id=?",stock)).isZero();
    }
    Entry add(long user,String symbol){return service.add(user,IDS.get(symbol));}
    String tryAdd(long user,String symbol){try{return add(user,symbol).state();}catch(BusinessException e){return e.errorCode().name();}}
    void freeLegacyBudget(){jdbc.update("UPDATE market_subscription_targets SET legacy_hold=FALSE,slot_reserved=FALSE,state='CANCELLED'");}
    long user(){String email=UUID.randomUUID()+"@monitor.test";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'test','Fixture')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    AlertRuleResponse rule(long user,long stock,String name,BigDecimal threshold){return ruleService.create(user,new SaveAlertRuleRequest(stock,name,null,AlertDirection.ABOVE,threshold,AlertTriggerMode.ONCE,null));}
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    List<Long> facts(long stock){return List.of(count("SELECT COUNT(*) FROM quote_events WHERE stock_id=?",stock),count("SELECT COUNT(*) FROM alert_triggers t JOIN quote_events q ON q.id=t.quote_event_id WHERE q.stock_id=?",stock),count("SELECT COUNT(*) FROM outbox_events"),count("SELECT COUNT(*) FROM notifications"));}
    QuoteCandidate candidate(long stock,String price,Instant at){return new QuoteCandidate("monitor-fixture:"+UUID.randomUUID(),stock,new BigDecimal(price),LocalDateTime.ofInstant(at,ZoneOffset.UTC),"ALPACA_IEX",null,at,"ALPACA","iex","monitor-fixture:2026-10-28",null);}
    String token(long user){Instant now=Instant.now();var claims=JwtClaimsSet.builder().issuer("price-pulse").subject(Long.toString(user)).issuedAt(now).expiresAt(now.plusSeconds(300)).build();return "Bearer "+jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();}
}
