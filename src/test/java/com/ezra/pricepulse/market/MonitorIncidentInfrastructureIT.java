package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.monitoring.*;
import com.ezra.pricepulse.notification.NotificationService;
import com.ezra.pricepulse.stock.StockMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

/** Synthetic users, quotes and faults only; no provider connector or production broker. */
@SpringBootTest(properties={"price-pulse.alpaca.enabled=false","price-pulse.messaging.enabled=false","price-pulse.monitoring.enabled=false"})
@ActiveProfiles("integration") @Testcontainers @Import(MonitorIncidentInfrastructureIT.Fixtures.class)
class MonitorIncidentInfrastructureIT {
    static final Instant NOW=Instant.parse("2026-11-02T15:00:00Z");
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("monitor_incident_it").withCommand("--log-bin-trust-function-creators=1");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"monitor-only-test-secret-at-least-32-bytes");
    }
    static class TestClock extends Clock {volatile Instant now=NOW;public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {@Bean @Primary TestClock monitorClock(){return new TestClock();}}
    @Autowired JdbcTemplate jdbc;@Autowired MonitorIncidentService service;@Autowired MonitorIncidentMapper incidents;
    @Autowired MonitoringMapper subscriptions;@Autowired MarketDataMapper data;@Autowired StockMapper stocks;@Autowired MarketHistoryCache cache;
    @Autowired QuoteIngestionService quotes;@Autowired AlertRuleService rules;@Autowired NotificationService notices;
    @Autowired TestClock clock;@Autowired ObjectMapper json;
    MarketFeedHealth health;RealMarketRuntime runtime;long user,stock;String symbol;
    @BeforeEach void fixture(){
        clock.now=NOW;health=new MarketFeedHealth();
        // Each test owns a new runtime epoch; close only the preceding synthetic fixture's open scopes.
        jdbc.update("UPDATE market_monitor_incidents SET active_scope=NULL,recovered_at=COALESCE(recovered_at,started_at)");
        jdbc.update("DELETE FROM user_stock_subscriptions");jdbc.update("UPDATE market_subscription_targets SET legacy_hold=FALSE,slot_reserved=FALSE,state='CANCELLED',intent_version=intent_version+1");
        user=user();symbol="M"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        jdbc.update("INSERT INTO stocks(symbol,name,market,currency) VALUES(?,'SYNTHETIC','US','USD')",symbol);stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol=?",Long.class,symbol);subscribe(user,stock);
    }
    @AfterEach void stop(){if(runtime!=null)runtime.stop();runtime=null;}
    long user(){String email=UUID.randomUUID()+"@example.invalid";jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'test','SYNTHETIC')",email);return jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);}
    void subscribe(long u,long s){jdbc.update("INSERT INTO market_subscription_targets(stock_id,slot_reserved) VALUES(?,TRUE) ON DUPLICATE KEY UPDATE slot_reserved=TRUE",s);subscriptions.subscribe(u,s);}
    void persist(){for(var f:health.faults()){service.record(f,clock.instant());health.acknowledgeFault(f.id(),f.revision());}}
    long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    List<String> payloads(){return jdbc.queryForList("SELECT payload FROM outbox_events WHERE event_type='MARKET_MONITORING' AND JSON_EXTRACT(payload,'$.userId')=? ORDER BY id",String.class,user);}
    @ParameterizedTest @ValueSource(ints={5,29})
    void shortOutageClosesWithoutEitherNotice(int seconds){
        health.streamFailure("SOCKET_CLOSED_1006",NOW);String id=health.connectionFaultId();persist();clock.now=NOW.plusSeconds(seconds);
        health.recoverConnection(id,clock.now);persist();
        assertThat(payloads()).isEmpty();assertThat(incidents.find(id).recoveredAt()).isEqualTo(MarketTime.utc(clock.now));assertThat(health.faults()).isEmpty();
    }
    @Test void retriesKeepFirstTimeAndOneSharedAlarmPerUserUntilOneRecovery(){
        long other=user();subscribe(other,stock);long stock2=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='AAPL'",Long.class);subscribe(user,stock2);
        health.streamFailure("SOCKET_CLOSED_1006",NOW);String id=health.connectionFaultId();
        for(int seconds:new int[]{5,19,29}){clock.now=NOW.plusSeconds(seconds);health.streamFailure("CONNECT_FAILED",clock.now);persist();}
        assertThat(payloads()).isEmpty();clock.now=NOW.plusSeconds(30);persist();persist();
        assertThat(payloads()).hasSize(1);assertThat(incidents.find(id).startedAt()).isEqualTo(MarketTime.utc(NOW));
        assertThat(count("SELECT COUNT(*) FROM market_monitor_deliveries WHERE incident_key=?",id)).isEqualTo(2);
        // Cancellation does not lose this user's already queued recovery confirmation.
        jdbc.update("UPDATE user_stock_subscriptions SET desired=FALSE WHERE user_id=?",other);
        clock.now=NOW.plusSeconds(40);health.recoverConnection(id,clock.now);persist();persist();assertThat(payloads()).hasSize(2);
        assertThat(count("SELECT COUNT(*) FROM market_monitor_deliveries WHERE incident_key=?",id)).isEqualTo(4);
        for(String payload:payloads()){notices.consume(payload);notices.consume(payload);}
        assertThat(notices.feed(user).totalCount()).isEqualTo(2);assertThat(notices.feed(other).totalCount()).isZero();
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id IN(SELECT id FROM alert_rules WHERE user_id=?)",user)).isZero();
    }
    @Test void reorderedAndConcurrentMessagesAreOwnedAndIdempotent()throws Exception{
        health.streamFailure("HEARTBEAT_TIMEOUT",NOW);clock.now=NOW.plusSeconds(30);persist();
        health.recoverConnection(health.connectionFaultId(),clock.now.plusSeconds(1));persist();var events=payloads();assertThat(events).hasSize(2);
        try(var pool=Executors.newFixedThreadPool(3)){
            var calls=List.of(pool.submit(()->notices.consume(events.getLast())),pool.submit(()->notices.consume(events.getFirst())),pool.submit(()->notices.consume(events.getLast())));
            for(var call:calls)call.get(10,TimeUnit.SECONDS);
        }
        var rows=notices.list(user,null,10);assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().eventType()).isEqualTo("MONITORING_RECOVERED");assertThat(rows.getLast().eventType()).isEqualTo("MONITORING_INTERRUPTED");
        assertThat(rows).allSatisfy(n->{assertThat(n.triggerId()).isNull();assertThat(n.ruleId()).isNull();assertThat(n.triggerPrice()).isNull();});
        var forged=json.readTree(events.getFirst()).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)forged).put("userId",user());
        assertThatThrownBy(()->notices.consume(forged.toString())).isInstanceOf(IllegalArgumentException.class);
        long outsider=user();assertThatThrownBy(()->notices.markRead(outsider,rows.getFirst().id())).isInstanceOf(RuntimeException.class);
        notices.markRead(user,rows.getFirst().id());notices.markAllRead(user);assertThat(notices.feed(user).unreadCount()).isZero();
    }
    @Test void failedOutboxTransactionRetriesTheSameFaultWithoutLostOrDuplicateIntent(){
        health.streamFailure("CONNECT_FAILED",NOW);String id=health.connectionFaultId();clock.now=NOW.plusSeconds(30);
        @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<RealMarketRuntime>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var coordinator=new MarketIncidentCoordinator(incidents,service,health,new AlpacaProperties(),provider,clock);
        jdbc.execute("CREATE TRIGGER incident_fail BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated monitor failure'");
        try{assertThatThrownBy(coordinator::reconcile).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("DROP TRIGGER incident_fail");}
        assertThat(incidents.find(id)).isNull();assertThat(payloads()).isEmpty();assertThat(health.connectionFaultId()).isEqualTo(id);
        coordinator.reconcile();coordinator.reconcile();assertThat(payloads()).hasSize(1);
    }
    @Test void persistedFaultRestoresAcrossProcessEpochAndNextIndependentFaultHasANewId(){
        health.streamFailure("CONNECT_FAILED",NOW);String first=health.connectionFaultId();clock.now=NOW.plusSeconds(30);persist();
        var restarted=new MarketFeedHealth();incidents.open().stream().filter(i->i.incidentKey().equals(first)).forEach(i->restarted.restoreFault(i.restore()));health=restarted;
        health.streamFailure("HANDSHAKE_TIMEOUT",NOW.plusSeconds(40));clock.now=NOW.plusSeconds(45);persist();assertThat(payloads()).hasSize(1);
        assertThat(health.connectionFaultId()).isEqualTo(first);assertThat(health.faults().getFirst().startedAt()).isEqualTo(NOW);
        health.recoverConnection(first,clock.now);persist();health.streamFailure("PING_FAILED",NOW.plusSeconds(50));String second=health.connectionFaultId();
        assertThat(second).isNotEqualTo(first);clock.now=NOW.plusSeconds(80);persist();assertThat(payloads()).hasSize(3);
    }
    @Test void quietMarketAndNoTradesDoNotProduceAnyFault(){
        health.connection("SUBSCRIBED");health.streamSubscribed(NOW,List.of(symbol));clock.now=NOW.plusSeconds(3600);
        health.snapshot(symbol,false,clock.now,null,true);health.snapshot(symbol,true,clock.now,null,true);persist();assertThat(payloads()).isEmpty();
    }
    @Test void realLaneOverflowReportsExactLossImmediatelyAndItsRecoveryCanReachAnOnceTarget()throws Exception{
        quotes.ingest(candidate("base","200","initial","STARTUP"),QuoteIngestionPolicy.LIVE);
        var rule=rules.create(user,new SaveAlertRuleRequest(stock,"Target",null,AlertDirection.ABOVE,new BigDecimal("205")));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(2);
        var observer=new MarketQuoteLane.Observer(){public void invalidated(String cause,long lost,String continuity){health.queueGap(stock,cause,lost,continuity,clock.now);}};
        try(var lane=new MarketQuoteLane(stock,1024,w->{
            if(w.trade().eventKey().equals("held")){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}
            var result=quotes.ingest(candidate(w.trade().eventKey(),w.trade().price().toPlainString(),w.continuity(),w.reason()),QuoteIngestionPolicy.LIVE);
            if(result.outcome()==QuoteOutcome.PROCESSED)health.queueAccepted(stock,w.continuity(),clock.now);done.countDown();
        },health::error,observer)){
            lane.offer(trade("held","200"));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<1024;i++)lane.offer(trade("discard-"+i,"200"));lane.offer(trade("new-baseline","210"));
            assertThat(health.faults().getFirst().discarded()).isEqualTo(1024);persist();assertThat(payloads()).hasSize(1);
            release.countDown();assertThat(done.await(5,TimeUnit.SECONDS)).isTrue();persist();assertThat(payloads()).hasSize(2);
            assertThat(rules.get(user,rule.id()).status()).isEqualTo(AlertRuleStatus.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT trigger_context FROM alert_triggers WHERE rule_id=?",String.class,rule.id())).contains("RECOVERY","QUEUE_OVERFLOW");
            assertThat(count("SELECT COUNT(*) FROM quote_events WHERE stock_id=?",stock)).isEqualTo(3);
        }
    }
    QuoteCandidate candidate(String key,String price,String continuity,String reason){return new QuoteCandidate(key+":"+stock,stock,new BigDecimal(price),MarketTime.utc(clock.now),"ALPACA_IEX",null,clock.now,"ALPACA","iex",continuity+":2026-11-02",reason);}
    AlpacaProtocol.Trade trade(String key,String price){return new AlpacaProtocol.Trade(symbol,key,new BigDecimal(price),clock.now,clock.now);}
    Wire bootRuntime(boolean dynamic){
        data.saveSession(new MarketDataModels.Session(LocalDate.parse("2026-11-02"),LocalDateTime.parse("2026-11-02T14:30:00"),LocalDateTime.parse("2026-11-02T21:00:00")),MarketTime.utc(NOW));
        var props=new AlpacaProperties();props.setEnabled(true);props.setBusinessWritesEnabled(true);props.setKeyId("fixture");props.setSecretKey("fixture");props.setSymbols(List.of("AAPL"));
        var monitoring=new MonitoringProperties();monitoring.setEnabled(dynamic);monitoring.setDynamicSubscriptionsEnabled(dynamic);var wire=new Wire();
        var history=new AlpacaDataClient(uri->{if(uri.getPath().equals("/v2/calendar"))return json.createArrayNode().add(json.createObjectNode().put("date","2026-11-02").put("open","09:30").put("close","16:00"));var empty=json.createObjectNode();empty.set("bars",json.createObjectNode());return empty;});
        assertThat(subscriptions.liveDemand()).extracting(com.ezra.pricepulse.monitoring.MonitoringModels.Demand::symbol).containsExactly(symbol);
        runtime=new RealMarketRuntime(props,data,stocks,cache,quotes,health,json,clock,monitoring,subscriptions,wire::connect,()->history);runtime.start();wire.auth();return wire;
    }
    @Test void staticCompatibilityDoesNotRequireADynamicIntentButStillRequiresActualAck(){
        jdbc.update("DELETE FROM user_stock_subscriptions WHERE user_id=?",user);
        stock=jdbc.queryForObject("SELECT id FROM stocks WHERE market='US' AND symbol='AAPL'",Long.class);symbol="AAPL";subscribe(user,stock);
        jdbc.update("UPDATE market_subscription_targets SET intent_version=7 WHERE stock_id=?",stock);var wire=bootRuntime(false);
        health.streamFailure("CONNECT_FAILED",NOW);String id=health.connectionFaultId();service.confirmRecovery(runtime,health,clock.now);
        assertThat(health.connectionFaultId()).isEqualTo(id);wire.ack();service.confirmRecovery(runtime,health,clock.now);
        assertThat(health.connectionFaultId()).isNull();
    }
    @Test void disconnectAlarmAuthWithoutAckThenRealTargetAndConfirmedRecoveryAreIndependent()throws Exception{
        var wire=bootRuntime(true);wire.ack();
        assertThat(health.confirms(symbol)).as("subscription ACK; commands=%s",wire.commands).isTrue();wire.trade(200);
        await(()->data.streamState(stock)!=null);var rule=rules.create(user,new SaveAlertRuleRequest(stock,"Recovered target",null,AlertDirection.ABOVE,new BigDecimal("205")));
        wire.listener.onClose(wire.socket,1006,"");String id=health.connectionFaultId();assertThat(id).isNotNull();clock.now=NOW.plusSeconds(30);persist();assertThat(payloads()).hasSize(1);
        await(()->wire.connections.get()==2);wire.auth();service.confirmRecovery(runtime,health,clock.now);assertThat(health.connectionFaultId()).isEqualTo(id);
        wire.ack();
        jdbc.execute("CREATE TRIGGER monitor_quote_fail BEFORE INSERT ON quote_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated processing failure'");
        try{wire.trade(210);await(()->"PROCESSING_FAILED_GAP".equals(health.snapshot(symbol,true,clock.now,null,true).lastError()));}
        finally{jdbc.execute("DROP TRIGGER monitor_quote_fail");}
        service.confirmRecovery(runtime,health,clock.now);assertThat(health.connectionFaultId()).isEqualTo(id);
        assertThat(rules.get(user,rule.id()).status()).isEqualTo(AlertRuleStatus.ACTIVE);
        wire.trade(210);await(()->rules.get(user,rule.id()).status()==AlertRuleStatus.COMPLETED);
        service.confirmRecovery(runtime,health,clock.now);assertThat(health.connectionFaultId()).isNull();persist();assertThat(payloads()).hasSize(2);
        String pricePayload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type='ALERT_TRIGGERED' AND JSON_EXTRACT(payload,'$.ruleId')=?",String.class,rule.id());
        notices.consume(payloads().getFirst());notices.consume(pricePayload);notices.consume(payloads().getLast());
        var feed=notices.feed(user);assertThat(feed.totalCount()).isEqualTo(3);assertThat(feed.latest()).extracting(n->n.title()).anySatisfy(t->assertThat(t).contains("恢复后确认达到"));
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule.id())).isEqualTo(1);
        assertThat(incidents.find(id).recoveredAt()).isNotNull();
    }
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertThat(condition.getAsBoolean()).isTrue();}
    class Wire {
        volatile WebSocket.Listener listener;volatile WebSocket socket;AtomicInteger connections=new AtomicInteger();long tradeId;List<String> commands=new CopyOnWriteArrayList<>();
        CompletableFuture<WebSocket> connect(WebSocket.Listener l){listener=l;socket=mock(WebSocket.class);var ws=socket;
            when(ws.sendText(anyString(),eq(true))).thenAnswer(a->{commands.add(a.getArgument(0));return CompletableFuture.completedFuture(ws);});when(ws.sendPing(any())).thenReturn(CompletableFuture.completedFuture(ws));
            when(ws.sendClose(anyInt(),anyString())).thenAnswer(a->{l.onClose(ws,1000,"");return CompletableFuture.completedFuture(ws);});l.onOpen(ws);connections.incrementAndGet();return CompletableFuture.completedFuture(ws);}
        void frame(Object value){try{listener.onText(socket,json.writeValueAsString(List.of(value)),true);}catch(Exception e){throw new AssertionError(e);}}
        void auth(){frame(Map.of("T","success","msg","authenticated"));}void ack(){frame(Map.of("T","subscription","trades",List.of(symbol)));}
        void trade(int price){frame(Map.of("T","t","S",symbol,"p",price,"s",100,"c",List.of("@"),"z","C","x","V","i",++tradeId,"t",clock.now.toString()));}
    }
}
