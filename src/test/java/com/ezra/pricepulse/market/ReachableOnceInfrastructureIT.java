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
    @Autowired AlertRuleMapper ruleMapper;
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
    AlertRuleResponse rule(String direction,String target){return rules.create(user,new SaveAlertRuleRequest(stock,"target",null,AlertDirection.valueOf(direction),new BigDecimal(target)));}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.equals("quote_events")?"stock_id":"rule_id")+"=?",Long.class,table.equals("quote_events")?stock:rules.list(user,stock,null,100).getFirst().id());}
    @ParameterizedTest @CsvSource({"ABOVE,200,210,205","BELOW,210,200,205"})
    void recoveryConfirmsReachabilityWithActualPriceTimeAndNoDuplicate(String direction,String before,String after,String target){
        quote("base",before,"a");var rule=rule(direction,target);assertThat(rule.status()).isEqualTo(AlertRuleStatus.ACTIVE);
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
        quote("base",price,"a");clock.now=NOW.plusSeconds(20);var rule=rule(direction,target);
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
        quote("base","210","a");clock.now=NOW.plusSeconds(121);var first=rule("ABOVE","205");assertThat(first.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        ready.set(false);var second=rule("ABOVE","206");assertThat(second.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(quote("same-price","210","a").matching().triggered()).isEqualTo(2);
    }
    @Test void oldInternalOrHistoricalPricesCannotActivateAtCreation(){
        quotes.ingest(new QuoteCandidate("internal:"+stock,stock,new BigDecimal("210"),MarketTime.utc(NOW),"TEST",null),QuoteIngestionPolicy.LIVE);
        assertThat(rule("ABOVE","205").status()).isEqualTo(AlertRuleStatus.ACTIVE);
    }
    @Test void clockSkewedFutureProcessingMetadataCannotConfirmCreation(){
        quote("base","210","a");jdbc.update("UPDATE quote_events SET processed_at=? WHERE stock_id=?",MarketTime.utc(NOW.plusSeconds(1)),stock);
        assertThat(rule("ABOVE","205").status()).isEqualTo(AlertRuleStatus.ACTIVE);
    }
    @Test void noMissingTradeIsInventedWhenRecoveryPriceDoesNotMeetTarget(){
        quote("base","200","a");var r=rule("ABOVE","205");clock.now=NOW.plusSeconds(10);
        assertThat(quote("recovery","200","b").matching().triggered()).isZero();assertThat(rules.get(user,r.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
    }
    @Test void normalCrossOldWatermarkAndDuplicatePayloadKeepExactlyOneSideEffect(){
        quote("base","200","a");var r=rule("ABOVE","205");clock.now=NOW.plusSeconds(10);
        assertThat(quotes.ingest(new QuoteCandidate("old:"+stock,stock,new BigDecimal("210"),MarketTime.utc(NOW.minusSeconds(1)),"ALPACA_IEX",null,clock.now,"ALPACA","iex","a:2026-11-02","STARTUP"),QuoteIngestionPolicy.LIVE).outcome()).isEqualTo(QuoteOutcome.DISCARDED_OUT_OF_ORDER);
        assertThat(rules.get(user,r.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(quote("cross","210","a").matching().triggered()).isEqualTo(1);
        assertThat(quote("cross","210","a").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        assertThatThrownBy(()->quote("cross","211","a")).isInstanceOf(com.ezra.pricepulse.common.error.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT trigger_context FROM alert_triggers WHERE rule_id=?",String.class,r.id())).contains("LIVE");
    }
    @ParameterizedTest @CsvSource({"b,RECOVERY","a,LIVE"})
    void delayedTradeConfirmationIsNotBackdatedIntoTheGapOrBeforeRuleCreation(String generation,String label){
        quote("base","200","a");clock.now=NOW.plusSeconds(10);var r=rule("ABOVE","205");clock.now=NOW.plusSeconds(60);
        var q=new QuoteCandidate("delayed:"+stock,stock,new BigDecimal("210"),MarketTime.utc(NOW.plusSeconds(5)),"ALPACA_IEX",null,clock.now,"ALPACA","iex",generation+":2026-11-02","RECONNECT");
        assertThat(quotes.ingest(q,QuoteIngestionPolicy.LIVE).matching().triggered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_time FROM alert_triggers WHERE rule_id=?",LocalDateTime.class,r.id())).isEqualTo(MarketTime.utc(clock.now));
        var events=timeline.events(user,stock,NOW.plusSeconds(55),clock.now.plusSeconds(1),null).events();
        assertThat(events).hasSize(1);assertThat(events.getFirst().eventTime()).isEqualTo(clock.now);
        assertThat(events.getFirst().triggerContext().path("confirmation").asText()).isEqualTo(label);
        assertThat(events.getFirst().triggerContext().path("quoteEventTime").asText()).isEqualTo(NOW.plusSeconds(5).toString());
        assertThat(jdbc.queryForObject("SELECT event_time_ns FROM quote_events WHERE id=?",Long.class,events.getFirst().quoteId())).isEqualTo(MarketTime.nanos(NOW.plusSeconds(5)));
    }
    @Test void legacyCacheTokenMissingIndexAndRollbackStillRecoverReachableCandidates(){
        quote("base","210","a");ready.set(false);var r=rule("ABOVE","205");var s=stocks.findActiveById(stock);
        redis.delete(RuleIndex.key(stock));redis.opsForZSet().add(RuleIndex.key(stock),"@"+s.ruleIndexToken(),-1);
        assertThat(index.candidates(s,new BigDecimal("210"))).contains(r.id());
        redis.delete(RuleIndex.key(stock));assertThat(quote("same","210","a").matching().triggered()).isEqualTo(1);
        assertThat(index.candidates(stocks.findActiveById(stock),new BigDecimal("210"))).doesNotContain(r.id());
    }
    @Test void creationAndQuoteSerializeToOneTransition()throws Exception{
        quote("base","200","a");clock.now=NOW.plusSeconds(1);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return rule("ABOVE","205");});
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
        try{assertThatThrownBy(()->rule("ABOVE","205")).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("DROP TRIGGER reachable_fail");}
        assertThat(rules.list(user,stock,null,100)).isEmpty();assertThat(stocks.findActiveById(stock).ruleIndexToken()).isEqualTo(token);
        assertThat(rule("ABOVE","205").status()).isEqualTo(AlertRuleStatus.COMPLETED);
    }
    @ParameterizedTest @CsvSource({"ABOVE,210,220,205","BELOW,200,190,205"})
    void activeEditConfirmsFreshTargetAtomicallyAndCompletedCannotBeEditedOrReactivated(String direction,String price,String oldTarget,String target){
        quote("base",price,"a");var r=rule(direction,oldTarget);clock.now=NOW.plusSeconds(20);
        var updated=rules.update(user,r.id(),new UpdateAlertRuleRequest(r.version(),new SaveAlertRuleRequest(stock,"edited",null,AlertDirection.valueOf(direction),new BigDecimal(target))));
        assertThat(updated.status()).isEqualTo(AlertRuleStatus.COMPLETED);assertThat(updated.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quote_events WHERE stock_id=?",Long.class,stock)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT trigger_context FROM alert_triggers WHERE rule_id=?",String.class,r.id())).contains("RULE_UPDATED");
        assertThat(jdbc.queryForObject("SELECT event_time FROM alert_triggers WHERE rule_id=?",LocalDateTime.class,r.id())).isEqualTo(MarketTime.utc(clock.now));
        String token=stocks.findActiveById(stock).ruleIndexToken();
        assertThatThrownBy(()->rules.update(user,r.id(),new UpdateAlertRuleRequest(updated.version(),new SaveAlertRuleRequest(stock,"cannot restart",null,AlertDirection.valueOf(direction),new BigDecimal(oldTarget)))))
                .isInstanceOfSatisfying(com.ezra.pricepulse.common.error.BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(com.ezra.pricepulse.common.error.ErrorCode.CONFLICT));
        assertThat(ruleMapper.complete(r.id(),updated.version(),MarketTime.utc(clock.now))).isZero();
        assertThat(rules.get(user,r.id())).isEqualTo(updated);assertThat(stocks.findActiveById(stock).ruleIndexToken()).isEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isEqualTo(1);
    }
    @Test void stalePriceAfterEditWaitsForTheNextTrustedPointEvenWithoutPriceMovement(){
        quote("base","210","a");var r=rule("ABOVE","220");clock.now=NOW.plusSeconds(121);
        var updated=rules.update(user,r.id(),new UpdateAlertRuleRequest(r.version(),new SaveAlertRuleRequest(stock,"waiting",null,AlertDirection.ABOVE,new BigDecimal("205"))));
        assertThat(updated.status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isZero();
        assertThat(quote("same-price-after-edit","210","a").matching().triggered()).isEqualTo(1);
    }
    @Test void concurrentEditAndQuoteCannotOverwriteTheFinalState()throws Exception{
        quote("base","200","a");var r=rule("ABOVE","205");clock.now=NOW.plusSeconds(1);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var edit=pool.submit(()->{start.await();try{return (Object)rules.update(user,r.id(),new UpdateAlertRuleRequest(r.version(),new SaveAlertRuleRequest(stock,"concurrent",null,AlertDirection.ABOVE,new BigDecimal("206"))));}catch(com.ezra.pricepulse.common.error.BusinessException e){assertThat(e.errorCode()).isEqualTo(com.ezra.pricepulse.common.error.ErrorCode.CONFLICT);return e;}});
            var trade=pool.submit(()->{start.await();return quote("concurrent-up","210","a");});start.countDown();
            edit.get(10,TimeUnit.SECONDS);trade.get(10,TimeUnit.SECONDS);
        }
        assertThat(rules.get(user,r.id()).status()).isEqualTo(AlertRuleStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE JSON_EXTRACT(payload,'$.ruleId')=?",Long.class,r.id())).isEqualTo(1);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void concurrentTradesAndDuplicateIdentityCompleteOnlyOnce(boolean sameIdentity)throws Exception{
        quote("base","200","a");var r=rule("ABOVE","205");clock.now=NOW.plusSeconds(1);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return quote("concurrent-a","210","a");});
            var b=pool.submit(()->{start.await();return quote(sameIdentity?"concurrent-a":"concurrent-b","210","a");});start.countDown();
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(q->q.matching()!=null).mapToInt(q->q.matching().triggered()).sum()).isEqualTo(1);
            assertThat(results.stream().filter(q->q.outcome()==QuoteOutcome.DUPLICATE).count()).isEqualTo(sameIdentity?1:0);
        }
        assertThat(rules.get(user,r.id()).status()).isEqualTo(AlertRuleStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quote_events WHERE stock_id=?",Long.class,stock)).isEqualTo(sameIdentity?2:3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isEqualTo(1);
    }
    @Test void editOutboxFailureRollsBackConfigurationCompletionAndTokenAndCanRetry(){
        quote("base","210","a");var r=rule("ABOVE","220");var token=stocks.findActiveById(stock).ruleIndexToken();
        var request=new UpdateAlertRuleRequest(r.version(),new SaveAlertRuleRequest(stock,"edited",null,AlertDirection.ABOVE,new BigDecimal("205")));
        jdbc.execute("CREATE TRIGGER edit_reachable_fail BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated edit failure'");
        try{assertThatThrownBy(()->rules.update(user,r.id(),request)).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("DROP TRIGGER edit_reachable_fail");}
        assertThat(rules.get(user,r.id())).isEqualTo(r);assertThat(stocks.findActiveById(stock).ruleIndexToken()).isEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",Long.class,r.id())).isZero();
        assertThat(rules.update(user,r.id(),request).status()).isEqualTo(AlertRuleStatus.COMPLETED);
    }
}
