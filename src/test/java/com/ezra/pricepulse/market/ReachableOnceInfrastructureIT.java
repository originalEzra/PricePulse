package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.notification.NotificationService;
import com.ezra.pricepulse.stock.StockMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest @ActiveProfiles("integration") @Testcontainers
@Import(ReachableOnceInfrastructureIT.Fixtures.class)
class ReachableOnceInfrastructureIT {
    static final Instant NOW=Instant.parse("2026-11-02T15:00:00Z");
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("reachable_once_it").withCommand("--log-bin-trust-function-creators=1");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"reachable-only-test-key-at-least-32-bytes");
        r.add("price-pulse.alpaca.enabled",()->false);r.add("price-pulse.messaging.enabled",()->false);r.add("price-pulse.monitoring.enabled",()->false);
    }
    static class FixtureClock extends Clock {
        volatile Instant now=NOW;
        public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean @Primary FixtureClock fixtureClock(){return new FixtureClock();}
        @Bean AtomicBoolean ready(){return new AtomicBoolean(true);}
        @Bean @Primary QuoteReadiness quoteReady(AtomicBoolean ready){return (s,state)->ready.get();}
    }
    @Autowired JdbcTemplate jdbc;@Autowired QuoteIngestionService quotes;@Autowired AlertRuleService rules;
    @Autowired RuleIndex index;@Autowired StockMapper stocks;@Autowired StringRedisTemplate redis;
    @Autowired NotificationService notices;@Autowired FixtureClock clock;@Autowired AtomicBoolean ready;
    @Autowired MarketTimelineService timeline;
    long user,stock;
    @BeforeEach void fixture(){
        clock.now=NOW;ready.set(true);String suffix=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'fixture','SYNTHETIC')",suffix+"@example.invalid");user=jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,suffix+"@example.invalid");
        String symbol="R"+suffix.replace("-", "").substring(0,9);
        jdbc.update("INSERT INTO stocks(symbol,name,market,currency) VALUES(?,'Synthetic only','US','USD')",symbol);stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol=?",Long.class,symbol);
    }
    QuoteResult quote(String key,String price,String generation){
        return quotes.ingest(new QuoteCandidate(key+":"+stock,stock,new BigDecimal(price),MarketTime.utc(clock.now.minusMillis(1)),"ALPACA_IEX",null,clock.now,"ALPACA","iex",generation+":2026-11-02",generation.equals("a")?"STARTUP":"RECONNECT"),QuoteIngestionPolicy.LIVE);
    }
    AlertRuleResponse rule(String direction,String mode,String target,String rearm){return rules.create(user,new SaveAlertRuleRequest(stock,"target",null,AlertDirection.valueOf(direction),new BigDecimal(target),AlertTriggerMode.valueOf(mode),rearm==null?null:new BigDecimal(rearm)));}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("quote_events")?"stock_id":"rule_id")+"=?",Long.class,table.equals("quote_events")?stock:rules.list(user,stock,null,100).getFirst().id());}
    @ParameterizedTest @CsvSource({"ABOVE,200,210,205","BELOW,210,200,205"})
    void recoveryConfirmsReachabilityWithActualPriceTimeAndNoDuplicate(String direction,String before,String after,String target){
        quote("base",before,"a");var rule=rule(direction,"ONCE",target,null);assertThat(rule.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        clock.now=NOW.plusSeconds(10);var confirmed=quote("recovery",after,"b");assertThat(confirmed.matching().triggered()).isEqualTo(1);
        assertThat(rules.get(user,rule.id()).status()).isEqualTo(AlertRuleStatus.COMPLETED);
        var row=jdbc.queryForMap("SELECT trigger_price,event_time,trigger_context FROM alert_triggers WHERE rule_id=?",rule.id());
        assertThat(row.get("trigger_price").toString()).isEqualTo(new BigDecimal(after).setScale(4).toString());
        assertThat(row.get("trigger_context").toString()).contains("RECOVERY","TARGET_REACHED","RECONNECT");
        assertThat(quote("recovery",after,"b").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        clock.now=clock.now.plusSeconds(1);assertThat(quote("steady",after,"b").matching().triggered()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,rule.id())).isEqualTo(1);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=(SELECT id FROM alert_triggers WHERE rule_id=?)",String.class,rule.id());
        notices.consume(payload);notices.consume(payload);
        assertThat(jdbc.queryForObject("SELECT title FROM notifications WHERE user_id=?",String.class,user)).contains("恢复后确认达到");
    }
    @ParameterizedTest @CsvSource({"ABOVE,210,205","BELOW,200,205"})
    void freshCurrentQuoteCompletesCreationInItsTransactionWithoutCreatingAnotherQuote(String direction,String price,String target){
        quote("base",price,"a");clock.now=NOW.plusSeconds(20);var rule=rule(direction,"ONCE",target,null);
        assertThat(rule.status()).isEqualTo(AlertRuleStatus.COMPLETED);assertThat(rule.version()).isEqualTo(1);
        assertThat(count("quote_events")).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT event_time,trigger_context FROM alert_triggers WHERE rule_id=?",rule.id());
        assertThat(jdbc.queryForObject("SELECT event_time FROM alert_triggers WHERE rule_id=?",LocalDateTime.class,rule.id())).isEqualTo(MarketTime.utc(clock.now));
        assertThat(row.get("trigger_context").toString()).contains("RULE_CREATED");
        var events=timeline.events(user,stock,NOW.plusSeconds(1),clock.now.plusSeconds(1),null).events();
        assertThat(events).hasSize(1);assertThat(events.getFirst().eventTime()).isEqualTo(clock.now);
        assertThat(events.getFirst().triggerContext().path("quoteEventTime").asText()).isEqualTo(NOW.minusMillis(1).toString());
    }
    @Test void staleOrUnconfirmedReferenceWaitsForANewPointEvenAtTheSamePrice(){
        quote("base","210","a");clock.now=NOW.plusSeconds(121);var first=rule("ABOVE","ONCE","205",null);assertThat(first.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        ready.set(false);var second=rule("ABOVE","ONCE","206",null);assertThat(second.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(quote("same-price","210","a").matching().triggered()).isEqualTo(2);
    }
    @Test void oldInternalOrHistoricalPricesCannotActivateAtCreation(){
        quotes.ingest(new QuoteCandidate("internal:"+stock,stock,new BigDecimal("210"),MarketTime.utc(NOW),"TEST",null),QuoteIngestionPolicy.LIVE);
        assertThat(rule("ABOVE","ONCE","205",null).status()).isEqualTo(AlertRuleStatus.ACTIVE);
    }
    @Test void noMissingTradeIsInventedWhenRecoveryPriceDoesNotMeetTarget(){
        quote("base","200","a");var r=rule("ABOVE","ONCE","205",null);clock.now=NOW.plusSeconds(10);
        assertThat(quote("recovery","200","b").matching().triggered()).isZero();assertThat(rules.get(user,r.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
    }
    @Test void normalCrossOldWatermarkAndDuplicatePayloadKeepExactlyOneSideEffect(){
        quote("base","200","a");var r=rule("ABOVE","ONCE","205",null);clock.now=NOW.plusSeconds(10);
        assertThat(quotes.ingest(new QuoteCandidate("old:"+stock,stock,new BigDecimal("210"),MarketTime.utc(NOW.minusSeconds(1)),"ALPACA_IEX",null,clock.now,"ALPACA","iex","a:2026-11-02","STARTUP"),QuoteIngestionPolicy.LIVE).outcome()).isEqualTo(QuoteOutcome.DISCARDED_OUT_OF_ORDER);
        assertThat(rules.get(user,r.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(quote("cross","210","a").matching().triggered()).isEqualTo(1);
        assertThat(quote("cross","210","a").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        assertThatThrownBy(()->quote("cross","211","a")).isInstanceOf(com.ezra.pricepulse.common.error.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT trigger_context FROM alert_triggers WHERE rule_id=?",String.class,r.id())).contains("LIVE");
    }
    @Test void legacyCacheTokenMissingIndexAndRollbackStillRecoverReachableCandidates(){
        quote("base","210","a");ready.set(false);var r=rule("ABOVE","ONCE","205",null);var s=stocks.findActiveById(stock);
        redis.delete(RuleIndex.key(stock));redis.opsForZSet().add(RuleIndex.key(stock),"@"+s.ruleIndexToken(),-1);
        assertThat(index.candidates(s,new BigDecimal("210"),new BigDecimal("210"))).contains(r.id());
        redis.delete(RuleIndex.key(stock));assertThat(quote("same","210","a").matching().triggered()).isEqualTo(1);
        assertThat(index.candidates(stocks.findActiveById(stock),new BigDecimal("210"),new BigDecimal("210"))).doesNotContain(r.id());
    }
    @Test void creationAndQuoteSerializeToOneTransition()throws Exception{
        quote("base","200","a");clock.now=NOW.plusSeconds(1);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return rule("ABOVE","ONCE","205",null);});
            var b=pool.submit(()->{start.await();return quote("up","210","a");});start.countDown();
            long id=a.get(10,TimeUnit.SECONDS).id();b.get(10,TimeUnit.SECONDS);
            assertThat(rules.get(user,id).status()).isEqualTo(AlertRuleStatus.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE JSON_EXTRACT(payload,'$.ruleId')=?",Long.class,id)).isEqualTo(1);
        }
    }
    @Test void creationOutboxFailureRollsBackRuleTriggerAndIndexToken(){
        quote("base","210","a");var token=stocks.findActiveById(stock).ruleIndexToken();
        jdbc.execute("CREATE TRIGGER reachable_fail BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated failure'");
        try{assertThatThrownBy(()->rule("ABOVE","ONCE","205",null)).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("DROP TRIGGER reachable_fail");}
        assertThat(rules.list(user,stock,null,100)).isEmpty();assertThat(stocks.findActiveById(stock).ruleIndexToken()).isEqualTo(token);
        assertThat(rule("ABOVE","ONCE","205",null).status()).isEqualTo(AlertRuleStatus.COMPLETED);
    }
    @Test void repeatStillWaitsForACrossAndRearmsWithoutSamePointRetrigger(){
        quote("base","210","a");var r=rule("ABOVE","REPEAT","205","200");assertThat(r.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(quote("flat","210","a").matching().triggered()).isZero();clock.now=NOW.plusSeconds(1);
        assertThat(quote("low","200","a").matching().triggered()).isZero();clock.now=NOW.plusSeconds(2);
        assertThat(quote("high","210","a").matching().triggered()).isEqualTo(1);clock.now=NOW.plusSeconds(3);
        assertThat(quote("recovery","210","b").matching().triggered()).isZero();
    }
}
