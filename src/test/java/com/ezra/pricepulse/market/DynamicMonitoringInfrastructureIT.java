package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.monitoring.*;
import com.ezra.pricepulse.monitoring.MonitoringModels.*;
import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

/** Real MySQL/Redis/ingestion, deterministic provider protocol. No provider socket or production broker. */
@SpringBootTest
@ActiveProfiles("integration")
@Import(DynamicMonitoringInfrastructureIT.Fixtures.class)
@Testcontainers
class DynamicMonitoringInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("dynamic_monitor_it");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"isolated-dynamic-test-key-at-least-32-bytes");
        r.add("price-pulse.monitoring.enabled",()->true);r.add("price-pulse.monitoring.reconcile-ms",()->3600000);
        r.add("price-pulse.monitoring.dynamic-subscriptions-enabled",()->true);
        r.add("price-pulse.alpaca.enabled",()->false);r.add("price-pulse.messaging.enabled",()->false);
    }
    static class Bridge implements SubscriptionTransport {
        volatile DynamicSubscriptionTransport delegate;
        public boolean dynamicAvailable(){return true;}
        public boolean supports(String symbol){return delegate!=null&&delegate.supports(symbol);}
        public Observation observe(String symbol){return delegate==null?new Observation("PENDING",null,"AWAITING_UPSTREAM_CONFIRMATION"):delegate.observe(symbol);}
        public void reconcile(){if(delegate!=null)delegate.reconcile();}
        public boolean removalConfirmed(String symbol){return delegate!=null&&delegate.removalConfirmed(symbol);}
        public boolean failureReleaseConfirmed(String symbol){return delegate!=null&&delegate.failureReleaseConfirmed(symbol);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean @Primary Bridge bridge(){return new Bridge();}
        @Bean @Primary Clock fixtureClock(){return Clock.offset(Clock.systemUTC(),Duration.between(Instant.now(),Instant.parse("2026-10-30T14:00:00Z")));}
        @Bean @Primary AlpacaAssetClient fixtureAssets(ObjectMapper json,Clock clock){
            return new AlpacaAssetClient(path->{
                var all=json.createArrayNode();MonitoringInfrastructureIT.IDS.forEach((s,id)->all.add(json.createObjectNode().put("id",id).put("symbol",s).put("name",s)
                        .put("class","us_equity").put("exchange","NASDAQ").put("status","active").put("tradable",true)));
                if(path.contains("?"))return all;
                for(JsonNode a:all)if(path.endsWith(a.path("id").asText()))return a;
                throw new IllegalArgumentException("Unknown fixture asset");
            },clock);
        }
    }
    @Autowired MonitoringService service;
    @Autowired MonitoringMapper store;
    @Autowired MonitoringProperties monitoring;
    @Autowired Bridge bridge;
    @Autowired MarketDataMapper data;
    @Autowired StockMapper stocks;
    @Autowired MarketHistoryCache cache;
    @Autowired QuoteIngestionService ingestion;
    @Autowired MarketFeedHealth health;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertRuleService rules;
    RealMarketRuntime runtime;
    Wire wire;
    @BeforeEach void prepare(){
        jdbc.update("DELETE FROM user_stock_subscriptions");
        jdbc.update("UPDATE market_subscription_targets SET legacy_hold=FALSE,slot_reserved=FALSE,state='CANCELLED',intent_version=intent_version+1,version=version+1,confirmed_epoch=NULL,error_code=NULL");
        jdbc.update("UPDATE market_subscription_control SET symbol_limit=2 WHERE id=1");
        jdbc.update("UPDATE alert_rules SET status='DELETED',version=version+1 WHERE status<>'DELETED'");
        jdbc.update("UPDATE stocks SET rule_index_token=UUID()");
        data.saveSession(new MarketDataModels.Session(LocalDate.of(2026,10,30),LocalDateTime.parse("2026-10-30T13:30:00"),LocalDateTime.parse("2026-10-30T20:00:00")),MarketTime.utc(clock.instant()));
        monitoring.setDynamicSubscriptionsEnabled(true);monitoring.setAdoptLegacyHolds(false);boot();
    }
    @AfterEach void stop(){if(runtime!=null)runtime.stop();bridge.delegate=null;monitoring.setAdoptLegacyHolds(false);}
    void boot(){
        var props=new AlpacaProperties();props.setEnabled(true);props.setBusinessWritesEnabled(true);props.setKeyId("synthetic");props.setSecretKey("synthetic");
        wire=new Wire();var historical=new AlpacaDataClient(uri->{
            if(uri.getPath().equals("/v2/calendar"))return json.createArrayNode().add(json.createObjectNode().put("date","2026-10-30").put("open","09:30").put("close","16:00"));
            var result=json.createObjectNode();result.set("bars",json.createObjectNode());result.putNull("next_page_token");return result;
        });
        runtime=new RealMarketRuntime(props,data,stocks,cache,ingestion,health,json,clock,monitoring,store,wire::connect,()->historical);
        bridge.delegate=new DynamicSubscriptionTransport(store,monitoring,props,runtime);runtime.start();wire.auth();
    }
    @Test void twoUsersOneSubscriptionWithoutRulesAndLastCancellationWaitsForAck()throws Exception{
        long a=user(),b=user();var one=add(a,"PLTR");var two=add(b,"PLTR");assertThat(one.state()).isEqualTo("PENDING");assertThat(two.state()).isEqualTo("PENDING");
        service.reconcile();assertThat(wire.sent("subscribe","PLTR")).isEqualTo(1);wire.ack();service.reconcile();
        assertThat(service.list(a).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");assertThat(count("SELECT COUNT(*) FROM alert_rules WHERE user_id IN (?,?)",a,b)).isZero();
        assertThat(store.reserved()).isEqualTo(1);cancel(a,one.stock().id());service.reconcile();assertThat(wire.sent("unsubscribe","PLTR")).isZero();
        assertThat(service.list(b).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");
        cancel(b,one.stock().id());service.reconcile();assertThat(wire.sent("unsubscribe","PLTR")).isEqualTo(1);assertThat(store.reserved()).isEqualTo(1);
        wire.ack();service.reconcile();assertThat(store.reserved()).isZero();assertThat(wire.connections).isEqualTo(1);
    }
    @Test void delayedSubscribeAfterCancellationCannotConfirmOrFreeCapacityUntilCompensated()throws Exception{
        jdbc.update("UPDATE market_subscription_control SET symbol_limit=1 WHERE id=1");long u=user();var first=add(u,"PLTR");service.reconcile();cancel(u,first.stock().id());service.reconcile();
        assertThatThrownBy(()->add(user(),"SPY")).isInstanceOf(BusinessException.class).hasMessageContaining("名额");
        wire.ack();service.reconcile();assertThat(service.list(u).subscriptions()).isEmpty();assertThat(store.reserved()).isEqualTo(1);
        assertThat(wire.sent("unsubscribe","PLTR")).isEqualTo(1);wire.ack();service.reconcile();assertThat(store.reserved()).isZero();
        assertThat(add(user(),"SPY").state()).isEqualTo("PENDING");
    }
    @Test void failedAdditionDoesNotStopExistingStockAndRetryHasANewIntent()throws Exception{
        long u=user();var apple=add(u,"AAPL");service.reconcile();wire.ack();service.reconcile();
        var pltr=add(u,"PLTR");long old=store.getTarget(pltr.stock().id()).intentVersion();service.reconcile();wire.reject405();service.reconcile();
        var rows=service.list(u).subscriptions();assertThat(rows.stream().filter(r->r.stock().symbol().equals("AAPL")).findFirst().orElseThrow().state()).isEqualTo("CONFIRMED");
        var failed=rows.stream().filter(r->r.stock().symbol().equals("PLTR")).findFirst().orElseThrow();assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("UPSTREAM_LIMIT_REACHED");assertThat(store.reserved()).isEqualTo(1);
        assertThatThrownBy(()->rule(u,pltr.stock().id(),"No confirmation",100)).isInstanceOf(BusinessException.class);
        service.retry(u,pltr.stock().id(),failed.version());assertThat(store.getTarget(pltr.stock().id()).intentVersion()).isGreaterThan(old);
        service.reconcile();wire.ack();service.reconcile();assertThat(wire.sent("subscribe","PLTR")).isEqualTo(2);assertThat(store.reserved()).isEqualTo(2);
        assertThat(health.streamSnapshot().connectionAttempts()).isGreaterThanOrEqualTo(1);assertThat(store.demand(apple.stock().id())).isEqualTo(1);
    }
    @Test void restartRestoresPersistentDemandButNeverTrustsOldConfirmation()throws Exception{
        long u=user();var row=add(u,"PLTR");service.reconcile();wire.ack();service.reconcile();String old=store.getTarget(row.stock().id()).confirmedEpoch();
        rule(u,row.stock().id(),"Kept",100);long version=count("SELECT SUM(version) FROM alert_rules WHERE user_id=?",u);
        runtime.stop();boot();assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("PENDING");assertThat(wire.sent("subscribe","PLTR")).isEqualTo(1);
        wire.ack();service.reconcile();assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");
        assertThat(store.getTarget(row.stock().id()).confirmedEpoch()).isNotEqualTo(old);assertThat(count("SELECT SUM(version) FROM alert_rules WHERE user_id=?",u)).isEqualTo(version);
    }
    @Test void unrelatedAdditionKeepsOldContinuityAndFirstNewTradeOnlyBuildsBaseline()throws Exception{
        long u=user();var apple=add(u,"AAPL");service.reconcile();wire.ack();service.reconcile();
        var ar=rule(u,apple.stock().id(),"Existing crossing",100);tradeAndAwait("AAPL",apple.stock().id(),99);
        String before=jdbc.queryForObject("SELECT continuity_id FROM market_stream_state WHERE stock_id=?",String.class,apple.stock().id());
        var pltr=add(u,"PLTR");service.reconcile();wire.ack();service.reconcile();var pr=rule(u,pltr.stock().id(),"First must not alert",100);
        tradeAndAwait("PLTR",pltr.stock().id(),101);tradeAndAwait("AAPL",apple.stock().id(),101);await(()->count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",ar.id())==1);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",pr.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT continuity_id FROM market_stream_state WHERE stock_id=?",String.class,apple.stock().id())).isEqualTo(before);
        tradeAndAwait("PLTR",pltr.stock().id(),99);
        tradeAndAwait("PLTR",pltr.stock().id(),101);await(()->count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",pr.id())==1);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id IN (SELECT id FROM alert_triggers WHERE rule_id IN (?,?))",ar.id(),pr.id())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE user_id=?",u)).isZero(); // Broker disabled, no synthetic notification publisher.
        assertThat(wire.connections).isEqualTo(1);
    }
    @Test void readdDuringUnsubscribeIsPendingUntilTheNewAckAndDeletedRulesNeverReturn()throws Exception{
        long u=user();var row=add(u,"PLTR");service.reconcile();wire.ack();service.reconcile();var r=rule(u,row.stock().id(),"Removed",100);
        cancel(u,row.stock().id());service.reconcile();var next=add(u,"PLTR");service.reconcile();assertThat(next.state()).isEqualTo("PENDING");
        wire.ack();assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("PENDING");wire.ack();service.reconcile();
        assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");assertThat(count("SELECT COUNT(*) FROM alert_rules WHERE id=? AND status='DELETED'",r.id())).isEqualTo(1);
        assertThat(wire.sent("subscribe","PLTR")).isEqualTo(2);assertThat(store.reserved()).isEqualTo(1);
    }
    @Test void recreatedIntentCannotBeConfirmedByThePreviousSubscribeAck()throws Exception{
        long u=user();var row=add(u,"PLTR");service.reconcile();cancel(u,row.stock().id());add(u,"PLTR");service.reconcile();
        wire.ack();service.reconcile();assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("PENDING");
        assertThat(wire.sent("unsubscribe","PLTR")).isEqualTo(1);wire.ack();wire.ack();service.reconcile();
        assertThat(service.list(u).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");assertThat(wire.sent("subscribe","PLTR")).isEqualTo(2);
    }
    @Test void uncertainCancellationKeepsCapacityUntilAFreshConnectionProvesAbsence()throws Exception{
        jdbc.update("UPDATE market_subscription_control SET symbol_limit=1 WHERE id=1");long u=user();var row=add(u,"PLTR");service.reconcile();cancel(u,row.stock().id());
        runtime.stop();service.reconcile();assertThat(store.reserved()).isEqualTo(1);
        assertThatThrownBy(()->add(user(),"SPY")).isInstanceOf(BusinessException.class).hasMessageContaining("名额");
        boot();service.reconcile();assertThat(store.reserved()).isZero();assertThat(add(user(),"SPY").state()).isEqualTo("PENDING");
    }
    @Test void runtimeRestartFirstTradeRebuildsBaselineWithoutReplayingCrossing()throws Exception{
        long u=user();var row=add(u,"AAPL");service.reconcile();wire.ack();service.reconcile();var r=rule(u,row.stock().id(),"Recovery",100);
        tradeAndAwait("AAPL",row.stock().id(),99);
        long accepted=count("SELECT accepted_sequence FROM market_stream_state WHERE stock_id=?",row.stock().id());runtime.stop();boot();wire.ack();service.reconcile();
        tradeAndAwait("AAPL",row.stock().id(),101);assertThat(sequence(row.stock().id())).isGreaterThan(accepted);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",r.id())).isZero();
        tradeAndAwait("AAPL",row.stock().id(),99);
        tradeAndAwait("AAPL",row.stock().id(),101);await(()->count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",r.id())==1);
    }
    @Test void ambiguousProviderFailureKeepsSlotUntilReconnectionProvesAbsence()throws Exception{
        long u=user();add(u,"AAPL");service.reconcile();wire.ack();service.reconcile();add(u,"PLTR");service.reconcile();
        wire.reject(500);service.reconcile();assertThat(store.reserved()).isEqualTo(2);
        var failed=service.list(u).subscriptions().stream().filter(r->r.stock().symbol().equals("PLTR")).findFirst().orElseThrow();
        assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.errorCode()).isEqualTo("UPSTREAM_SUBSCRIPTION_FAILED");
        await(()->wire.connections==2);wire.auth();wire.ack();service.reconcile();assertThat(store.reserved()).isEqualTo(1);
        assertThat(service.list(u).subscriptions().stream().filter(r->r.stock().symbol().equals("AAPL")).findFirst().orElseThrow().state()).isEqualTo("CONFIRMED");
        assertThat(wire.sent("subscribe","PLTR")).isEqualTo(1); // No endless retry of a failed symbol.
    }
    @Test void anotherUsersNewRequestCanRetryAFailedGlobalTargetWithoutDuplicatingIt()throws Exception{
        long a=user(),b=user();var first=add(a,"PLTR");service.reconcile();wire.reject405();service.reconcile();
        long old=store.getTarget(first.stock().id()).intentVersion();assertThat(store.reserved()).isZero();
        assertThat(add(b,"PLTR").state()).isEqualTo("PENDING");assertThat(store.getTarget(first.stock().id()).intentVersion()).isGreaterThan(old);
        service.reconcile();wire.ack();service.reconcile();assertThat(store.reserved()).isEqualTo(1);assertThat(store.demand(first.stock().id())).isEqualTo(2);
        assertThat(service.list(a).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");assertThat(service.list(b).subscriptions().getFirst().state()).isEqualTo("CONFIRMED");
        assertThat(wire.sent("subscribe","PLTR")).isEqualTo(2);
    }
    @Test void legacyHoldRequiresExplicitAdoptionAndOrphanedEffectiveRulesBlockIt()throws Exception{
        long u=user();var row=add(u,"AAPL");jdbc.update("UPDATE market_subscription_targets SET legacy_hold=TRUE WHERE stock_id=?",row.stock().id());
        service.reconcile();wire.ack();service.reconcile();assertThat(store.getTarget(row.stock().id()).legacyHold()).isTrue();
        var r=rule(u,row.stock().id(),"Protected",100);jdbc.update("UPDATE user_stock_subscriptions SET desired=FALSE WHERE user_id=? AND stock_id=?",u,row.stock().id());
        monitoring.setAdoptLegacyHolds(true);service.reconcile();assertThat(store.getTarget(row.stock().id()).legacyHold()).isTrue();assertThat(wire.sent("unsubscribe","AAPL")).isZero();
        jdbc.update("UPDATE user_stock_subscriptions SET desired=TRUE WHERE user_id=? AND stock_id=?",u,row.stock().id());service.reconcile();
        assertThat(store.getTarget(row.stock().id()).legacyHold()).isFalse();assertThat(rules.get(u,r.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
        assertThat(wire.sent("subscribe","AAPL")).isEqualTo(1);assertThat(wire.sent("unsubscribe","AAPL")).isZero();
    }
    Entry add(long user,String symbol){return service.add(user,MonitoringInfrastructureIT.IDS.get(symbol));}
    void cancel(long user,long stock){var p=service.cancellation(user,stock);service.cancel(user,stock,new Cancel(p.version(),p.rulesDigest(),true));}
    long user(){String email=UUID.randomUUID()+"@dynamic.fixture";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'synthetic','Fixture')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    long sequence(long stock){return count("SELECT COALESCE((SELECT accepted_sequence FROM market_stream_state WHERE stock_id=?),0)",stock);}
    void tradeAndAwait(String symbol,long stock,int price)throws Exception{long before=sequence(stock);wire.trade(symbol,price);await(()->sequence(stock)>before);assertThat(jdbc.queryForObject("SELECT latest_price FROM stocks WHERE id=?",BigDecimal.class,stock)).isEqualByComparingTo(BigDecimal.valueOf(price));}
    AlertRuleResponse rule(long user,long stock,String name,int threshold){var r=rules.create(user,new SaveAlertRuleRequest(stock,name,null,AlertDirection.ABOVE,BigDecimal.valueOf(threshold),AlertTriggerMode.ONCE,null));jdbc.update("UPDATE alert_rules SET effective_at='2026-10-29 00:00:00' WHERE id=?",r.id());return r;}
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertThat(condition.getAsBoolean()).as("bounded isolated runtime condition").isTrue();}
    class Wire {
        final BlockingQueue<JsonNode> queue=new LinkedBlockingQueue<>();final List<JsonNode> sent=new CopyOnWriteArrayList<>();final Set<String> actual=new TreeSet<>();
        volatile WebSocket.Listener listener;volatile WebSocket socket;volatile int connections;long tradeId=Math.abs(System.nanoTime());
        CompletableFuture<WebSocket> connect(WebSocket.Listener listener){
            this.listener=listener;queue.clear();actual.clear();var ws=mock(WebSocket.class);socket=ws;
            when(ws.sendText(anyString(),eq(true))).thenAnswer(call->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("provider mutation outside business transaction").isFalse();
                var command=json.readTree((String)call.getArgument(0));sent.add(command);
                if(!command.path("action").asText().equals("auth"))queue.add(command);return CompletableFuture.completedFuture(ws);
            });
            when(ws.sendPing(any())).thenAnswer(call->{listener.onPong(ws,java.nio.ByteBuffer.wrap(new byte[]{1}));return CompletableFuture.completedFuture(ws);});
            when(ws.sendClose(anyInt(),anyString())).thenAnswer(call->{listener.onClose(ws,1000,"");return CompletableFuture.completedFuture(ws);});
            listener.onOpen(ws);connections++;return CompletableFuture.completedFuture(ws);
        }
        void frame(Object value){try{listener.onText(socket,json.writeValueAsString(List.of(value)),true);}catch(Exception e){throw new AssertionError(e);}}
        void auth(){frame(Map.of("T","success","msg","authenticated"));}
        void ack()throws Exception{var command=queue.poll(2,TimeUnit.SECONDS);assertThat(command).as("subscription command").isNotNull();String symbol=command.path("trades").get(0).asText();if(command.path("action").asText().equals("subscribe"))actual.add(symbol);else actual.remove(symbol);frame(Map.of("T","subscription","trades",List.copyOf(actual)));}
        void reject405()throws Exception{reject(405);}
        void reject(int code)throws Exception{assertThat(queue.poll(2,TimeUnit.SECONDS)).isNotNull();frame(Map.of("T","error","code",code,"msg","unprinted provider detail"));}
        long sent(String action,String symbol){return sent.stream().filter(n->n.path("action").asText().equals(action)&&n.path("trades").toString().contains("\""+symbol+"\"")).count();}
        void trade(String symbol,int price){frame(Map.of("T","t","S",symbol,"p",price,"s",100,"c",List.of("@"),"z","C","x","V","i",++tradeId,"t",clock.instant().toString()));}
    }
}
