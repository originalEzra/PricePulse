package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.alert.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.notification.NotificationService;
import com.ezra.pricepulse.outbox.RocketMqOutboxPublisher;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.containers.wait.strategy.Wait;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

/** All prices, users and rules belong exclusively to these disposable containers. */
@SpringBootTest(properties={"price-pulse.messaging.enabled=true","price-pulse.messaging.publisher-initial-delay-ms=600000","price-pulse.alpaca.enabled=false"})
@ActiveProfiles("integration")
@Import(RealQuoteTestSupport.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OpeningGapInfrastructureIT {
    private static final String RUN=UUID.randomUUID().toString();
    private static final Network NETWORK=Network.newNetwork();
    @Container static final GenericContainer<?> NAMESRV=new GenericContainer<>("apache/rocketmq:5.3.2")
            .withNetwork(NETWORK).withNetworkAliases("opening-namesrv").withExposedPorts(9876)
            .withEnv("JAVA_OPT_EXT","-Xms128m -Xmx128m -Xmn64m").withCommand("sh","mqnamesrv").waitingFor(Wait.forListeningPort());
    @Container static final TestBroker BROKER=new TestBroker().withNetwork(NETWORK).dependsOn(NAMESRV);
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("opening_gap");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        var bootstrap=new DefaultMQProducer("opening-bootstrap-"+RUN);
        bootstrap.setNamesrvAddr(NAMESRV.getHost()+":"+NAMESRV.getMappedPort(9876));
        bootstrap.setInstanceName("opening-bootstrap");
        try {
            bootstrap.start();
            boolean ready=false;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
            do {
                try {
                    bootstrap.createTopic("TBW102","opening-gap-"+RUN,2,Map.of());
                    if(!bootstrap.fetchPublishMessageQueues("opening-gap-"+RUN).isEmpty()){ready=true;break;}
                }catch(org.apache.rocketmq.client.exception.MQClientException notRegisteredYet){Thread.sleep(200);}
            }while(System.nanoTime()<deadline);
            if(!ready)throw new IllegalStateException("Isolated opening topic route did not become ready");
        }catch(org.apache.rocketmq.client.exception.MQClientException | InterruptedException error){throw new IllegalStateException("Isolated broker initialization failed",error);}
        finally { bootstrap.shutdown(); }
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("price-pulse.security.jwt.secret",()->"opening-test-only-secret-at-least-32-bytes");
        r.add("rocketmq.name-server",()->NAMESRV.getHost()+":"+NAMESRV.getMappedPort(9876));
        r.add("rocketmq.producer.group",()->"opening-producer-"+RUN);
        r.add("price-pulse.messaging.topic",()->"opening-gap-"+RUN);
        r.add("price-pulse.messaging.consumer-group",()->"opening-consumer-"+RUN);
    }
    @Autowired QuoteIngestionService ingestion;
    @Autowired MarketDataMapper data;
    @Autowired MarketHistoryCache cache;
    @Autowired MarketTimelineService timeline;
    @Autowired AlertEvaluationService evaluation;
    @Autowired AlertRuleService rules;
    @Autowired NotificationService notifications;
    @Autowired RocketMqOutboxPublisher publisher;
    @Autowired RealQuoteTestSupport.FixtureClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private static final Session PRIOR=new Session(LocalDate.parse("2026-10-28"),LocalDateTime.parse("2026-10-28T13:30:00"),LocalDateTime.parse("2026-10-28T20:00:00"));
    private static final Session CURRENT=new Session(LocalDate.parse("2026-10-29"),LocalDateTime.parse("2026-10-29T13:30:00"),LocalDateTime.parse("2026-10-29T20:00:00"));
    private static final Instant OPEN=CURRENT.open().plusNanos(123456789);
    private static final String GENERATION="00000000-0000-4000-8000-000000000001";
    private record Fixture(long user,long stock) {}

    /** Advertise the same free host/container port so native test clients can follow broker routes. */
    private static class TestBroker extends GenericContainer<TestBroker> {
        private final int listenPort;
        TestBroker() {
            super("apache/rocketmq:5.3.2");
            int port=freePort();listenPort=port;addFixedExposedPort(port,port);addFixedExposedPort(port-2,port-2);
            withEnv("JAVA_OPT_EXT","-Xms256m -Xmx256m -Xmn128m -XX:MaxDirectMemorySize=128m");
            withCopyToContainer(Transferable.of("brokerClusterName=opening-test\nbrokerName=opening-broker\nbrokerId=0\nbrokerIP1=127.0.0.1\nlistenPort="+port+"\nautoCreateTopicEnable=true\nbrokerRole=ASYNC_MASTER\nmappedFileSizeCommitLog=67108864\ndiskMaxUsedSpaceRatio=95\n"),"/home/rocketmq/conf/opening.conf");
            withCommand("sh","mqbroker","-n","opening-namesrv:9876","-c","/home/rocketmq/conf/opening.conf");
            waitingFor(Wait.forListeningPort());
        }
        private static int freePort(){try(var socket=new ServerSocket(0)){return socket.getLocalPort();}catch(java.io.IOException e){throw new IllegalStateException("No test broker port",e);}}
    }

    @ParameterizedTest @ValueSource(strings={"ABOVE","BELOW"})
    void actualOpeningCrossUsesTheTrustedCloseAndRetainsExactPriceContextAndConsumerIdempotency(String direction) throws Exception {
        var fixture=fixture();
        long rule=rule(fixture,direction,"ACTIVE","ONCE",direction.equals("ABOVE")?"105":"95",null,CURRENT.opensAt().minusHours(1));
        // Last accepted trade and the cached closing minute intentionally disagree.
        var at=PRIOR.close().minusSeconds(90);
        ingest(fixture,"previous",at,direction.equals("ABOVE")?"108":"92",GENERATION,"STARTUP");
        var actual=direction.equals("ABOVE")?"110.4567":"89.5432";
        var result=ingest(fixture,"opening",OPEN,actual,GENERATION,"STARTUP");
        assertThat(result.matching().triggered()).isEqualTo(1);
        assertThat(ingest(fixture,"opening",OPEN,actual,GENERATION,"STARTUP").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        assertThat(ingest(fixture,"next",OPEN.plusSeconds(1),actual,GENERATION,"STARTUP").matching().triggered()).isZero();
        var trigger=jdbc.queryForMap("SELECT * FROM alert_triggers WHERE rule_id=?",rule);
        assertThat((BigDecimal)trigger.get("trigger_price")).isEqualByComparingTo(actual);
        var context=json.readTree((String)trigger.get("trigger_context"));
        assertThat(context.path("kind").asText()).isEqualTo("OPENING_GAP");
        assertThat(context.path("referencePrice").asText()).isEqualTo("100.0000");
        assertThat(context.path("referenceTime").asText()).isEqualTo(PRIOR.close().toString());
        assertThat(context.path("ruleEffectiveAt").asText()).isEqualTo(CURRENT.open().minusSeconds(3600).toString());
        var events=timeline.events(fixture.user,fixture.stock,PRIOR.open(),OPEN.plusSeconds(2),null);
        assertThat(events.events()).hasSize(1);
        assertThat(events.events().getFirst().epochNanos()).isEqualTo(Long.toString(MarketTime.nanos(OPEN)));
        assertThat(events.events().getFirst().price()).isEqualTo(actual);
        assertThat(events.events().getFirst().triggerContext()).isEqualTo(context);
        assertThat(evaluation.history(fixture.user,rule,10).getFirst().triggerContext()).isEqualTo(context);
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=? AND aggregate_type='ALERT_TRIGGER'",String.class,trigger.get("id"));
        notifications.consume(payload);notifications.consume(payload);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",trigger.get("id"))).isEqualTo(1);
        var notice=notifications.list(fixture.user,null,10).getFirst();
        assertThat(notice.title()).contains("开盘跳空");assertThat(notice.content()).contains(actual,"100.0000");
        assertThat(jdbc.queryForObject("SELECT status FROM alert_rules WHERE id=?",String.class,rule)).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND aggregate_type='ALERT_TRIGGER'",trigger.get("id"))).isEqualTo(1);
        // Later historical revision does not rewrite the first comparison or generate new alerts.
        jdbc.update("UPDATE market_price_bars SET close_price=101,high_price=101,revision=revision+1 WHERE stock_id=?",fixture.stock);
        assertThat(evaluation.history(fixture.user,rule,10).getFirst().triggerContext()).isEqualTo(context);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule)).isEqualTo(1);
    }

    @Test void firstSessionOnlyTriggersEligibleActiveRulesAndNeverRevivesCompletedPausedOrDeletedRules() {
        var f=fixture();
        long valid=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().minusMinutes(1));
        long newAtOpen=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt());
        long editedAfterOpen=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().plusMinutes(1));
        List<Long> unchanged=new ArrayList<>(List.of(newAtOpen,editedAfterOpen));
        long badCreationTime=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().minusMinutes(1));
        jdbc.update("UPDATE alert_rules SET created_at=? WHERE id=?",CURRENT.opensAt().plusMinutes(1),badCreationTime);
        unchanged.add(badCreationTime);
        for(String status:List.of("COMPLETED","PAUSED","DELETED"))unchanged.add(rule(f,"ABOVE",status,"ONCE","105",null,CURRENT.opensAt().minusHours(1)));
        long cooldown=rule(f,"BELOW","COOLDOWN","REPEAT","95","100",CURRENT.opensAt().minusHours(1));
        ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");
        // Keep cooldown until the new day; its rearm must not depend on crossing from the reference.
        jdbc.update("UPDATE alert_rules SET status='COOLDOWN',version=version+1 WHERE id=?",cooldown);
        jdbc.update("UPDATE stocks SET rule_index_token=UUID() WHERE id=?",f.stock);
        var result=ingest(f,"late-first",OPEN.plusSeconds(300),"110",GENERATION,"STARTUP");
        assertThat(result.matching().triggered()).isEqualTo(1);assertThat(result.matching().rearmed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rule_id FROM alert_triggers WHERE quote_event_id=?",Long.class,result.eventId())).isEqualTo(valid);
        for(long id:unchanged)assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",id)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM alert_rules WHERE id=?",String.class,cooldown)).isEqualTo("ACTIVE");
    }

    @ParameterizedTest @ValueSource(strings={"STARTUP","RECONNECT","QUEUE_OVERFLOW","PROCESSING_FAILURE","STALE_OR_FUTURE","CORRECTION_OR_CANCEL"})
    void aChangedLaneGenerationProtectsTheFirstTradeEvenAcrossTheOpeningBoundary(String reason) {
        var f=fixture();long rule=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().minusHours(1));
        ingest(f,"previous",PRIOR.close().minusSeconds(30),"100",GENERATION,"STARTUP");
        var result=ingest(f,"recovery",OPEN,"110","00000000-0000-4000-8000-000000000002",reason);
        assertThat(result.matching().triggered()).isZero();
        assertThat(jdbc.queryForObject("SELECT baseline_reason FROM quote_events WHERE id=?",String.class,result.eventId())).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_rules WHERE id=?",String.class,rule)).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"missing","wrong-feed","wrong-adjustment","old-minute","incomplete-coverage"})
    void unavailableOrUntrustedClosingDataFallsBackToOneBaselineAndCannotBeFilledInLater(String kind) {
        var f=fixture();long rule=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().minusHours(1));
        if(kind.equals("missing"))jdbc.update("DELETE FROM market_price_bars WHERE stock_id=?",f.stock);
        if(kind.equals("wrong-feed"))jdbc.update("UPDATE market_price_bars SET feed='sip' WHERE stock_id=?",f.stock);
        if(kind.equals("wrong-adjustment"))jdbc.update("UPDATE market_price_bars SET adjustment='split' WHERE stock_id=?",f.stock);
        if(kind.equals("old-minute"))jdbc.update("UPDATE market_price_bars SET interval_start=interval_start-INTERVAL 1 MINUTE,interval_end=interval_end-INTERVAL 1 MINUTE WHERE stock_id=?",f.stock);
        if(kind.equals("incomplete-coverage"))jdbc.update("UPDATE market_history_coverage SET requested_to=? WHERE stock_id=?",PRIOR.closesAt().minusMinutes(1),f.stock);
        ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");
        var first=ingest(f,"open",OPEN,"110",GENERATION,"STARTUP");assertThat(first.matching().triggered()).isZero();
        assertThat(jdbc.queryForObject("SELECT baseline_reason FROM quote_events WHERE id=?",String.class,first.eventId())).isEqualTo("SESSION_REFERENCE_UNAVAILABLE");
        // A cache fill after accepting the first point cannot backfill an opening alert.
        cache(f.stock);
        assertThat(ingest(f,"later",OPEN.plusSeconds(1),"111",GENERATION,"STARTUP").matching().triggered()).isZero();
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule)).isZero();
    }

    @Test void duplicateAndHistoricalQueriesCannotRetriggerARepeatOpeningEvent() {
        var f=fixture();long rule=rule(f,"ABOVE","ACTIVE","REPEAT","105","100",CURRENT.opensAt().minusHours(1));
        ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");
        var first=ingest(f,"open",OPEN,"110",GENERATION,"STARTUP");assertThat(first.matching().triggered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM alert_rules WHERE id=?",String.class,rule)).isEqualTo("COOLDOWN");
        assertThat(ingest(f,"open",OPEN,"110",GENERATION,"STARTUP").outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        for(int i=0;i<2;i++)timeline.events(f.user,f.stock,PRIOR.open(),OPEN.plusSeconds(2),null);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule)).isEqualTo(1);
        assertThat(ingest(f,"rearm",OPEN.plusSeconds(1),"99",GENERATION,"STARTUP").matching().rearmed()).isEqualTo(1);
        assertThat(ingest(f,"cross",OPEN.plusSeconds(2),"106",GENERATION,"STARTUP").matching().triggered()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=? AND trigger_context IS NOT NULL",rule)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=? AND trigger_context IS NULL",rule)).isEqualTo(1);
    }

    @Test void ruleEditsAndResumeRefreshEligibilityWhileMatcherChangesDoNot() {
        var f=fixture();long id=rule(f,"ABOVE","ACTIVE","REPEAT","105","100",CURRENT.opensAt().minusHours(1));
        var old=jdbc.queryForObject("SELECT effective_at FROM alert_rules WHERE id=?",LocalDateTime.class,id);
        var current=rules.get(f.user,id);
        rules.update(f.user,id,new UpdateAlertRuleRequest(current.version(),new SaveAlertRuleRequest(f.stock,"Changed",null,AlertDirection.ABOVE,new BigDecimal("106"),AlertTriggerMode.REPEAT,new BigDecimal("100"))));
        assertThat(jdbc.queryForObject("SELECT effective_at FROM alert_rules WHERE id=?",LocalDateTime.class,id)).isAfter(old);
        current=rules.get(f.user,id);rules.pause(f.user,id,current.version());
        jdbc.update("UPDATE alert_rules SET effective_at=?,updated_at=updated_at WHERE id=?",old,id);
        current=rules.get(f.user,id);rules.resume(f.user,id,current.version());
        assertThat(jdbc.queryForObject("SELECT effective_at FROM alert_rules WHERE id=?",LocalDateTime.class,id)).isAfter(old);
        // Freeze an eligible test time, then prove actual matching does not redefine it.
        jdbc.update("UPDATE alert_rules SET effective_at=? WHERE id=?",old,id);
        ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");
        assertThat(ingest(f,"open",OPEN,"110",GENERATION,"STARTUP").matching().triggered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT effective_at FROM alert_rules WHERE id=?",LocalDateTime.class,id)).isEqualTo(old);
    }

    @Test void rulesCreatedAfterOpeningCannotUseTheEarlierCloseForALateFirstTrade() {
        var f=fixture();ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");
        var rule=rules.create(f.user,new SaveAlertRuleRequest(f.stock,"Created after open",null,AlertDirection.ABOVE,new BigDecimal("105"),AlertTriggerMode.ONCE,null));
        assertThat(rule.createdAt()).isAfter(CURRENT.opensAt());
        assertThat(ingest(f,"late-first",OPEN.plusSeconds(300),"110",GENERATION,"STARTUP").matching().triggered()).isZero();
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule.id())).isZero();
    }

    @Test void openingAlertTravelsThroughAnIsolatedRealBrokerAndDuplicateDeliveryRemainsIdempotent() throws Exception {
        var f=fixture();long id=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().minusHours(1));
        ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");
        assertThat(ingest(f,"open",OPEN,"110.4567",GENERATION,"STARTUP").matching().triggered()).isEqualTo(1);
        long trigger=jdbc.queryForObject("SELECT id FROM alert_triggers WHERE rule_id=?",Long.class,id);
        publisher.publishAvailable();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(count("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",trigger)==0 && System.nanoTime()<deadline)Thread.sleep(50);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",trigger)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_type='ALERT_TRIGGER' AND aggregate_id=?",String.class,trigger)).isEqualTo("SENT");
        assertThat(notifications.list(f.user,null,10).getFirst().title()).contains("开盘跳空");
        String payload=jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_type='ALERT_TRIGGER' AND aggregate_id=?",String.class,trigger);
        notifications.consume(payload);assertThat(count("SELECT COUNT(*) FROM notifications WHERE trigger_id=?",trigger)).isEqualTo(1);
    }

    @Test void quoteRuleTriggerAndOutboxStillRollbackAsOneTransaction() {
        var f=fixture();long rule=rule(f,"ABOVE","ACTIVE","ONCE","105",null,CURRENT.opensAt().minusHours(1));
        ingest(f,"previous",PRIOR.close().minusSeconds(90),"100",GENERATION,"STARTUP");var before=data.streamState(f.stock);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            assertThat(ingest(f,"open",OPEN,"110",GENERATION,"STARTUP").matching().triggered()).isEqualTo(1);
            throw new IllegalStateException("rollback-fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(data.streamState(f.stock)).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM alert_triggers WHERE rule_id=?",rule)).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE JSON_EXTRACT(payload,'$.stockId')=?",f.stock)).isZero();
        assertThat(ingest(f,"open",OPEN,"110",GENERATION,"STARTUP").matching().triggered()).isEqualTo(1);
    }

    private Fixture fixture() {
        String email=UUID.randomUUID()+"@opening.test",symbol="G"+UUID.randomUUID().toString().substring(0,15);
        jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,'test','Opening')",email);
        jdbc.update("INSERT INTO stocks(symbol,name,market,currency) VALUES(?,'Opening','US','USD')",symbol);
        var fixture=new Fixture(jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email),jdbc.queryForObject("SELECT id FROM stocks WHERE symbol=?",Long.class,symbol));
        cache(fixture.stock);return fixture;
    }
    private void cache(long stock) {
        var price=new BigDecimal("100");var fetched=PRIOR.closesAt().plusMinutes(5);
        var bar=new Bar(null,stock,PRIOR.closesAt().minusMinutes(1),PRIOR.closesAt(),price,price,price,price,1,fetched);
        cache.store(new AlpacaDataClient.Download(List.of(PRIOR,CURRENT),List.of(bar),1),List.of(stock),PRIOR.open(),MarketTime.instant(fetched));
    }
    private long rule(Fixture f,String direction,String status,String mode,String threshold,String rearm,LocalDateTime effective) {
        jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status,created_at,updated_at,effective_at) VALUES(?,?,'Opening rule',?,?,?,?,?,?,?,?)",
                f.user,f.stock,direction,new BigDecimal(threshold),mode,rearm==null?null:new BigDecimal(rearm),status,CURRENT.opensAt().minusHours(2),effective,effective);
        return jdbc.queryForObject("SELECT MAX(id) FROM alert_rules WHERE user_id=?",Long.class,f.user);
    }
    private QuoteResult ingest(Fixture f,String key,Instant at,String price,String generation,String reason) {
        var candidate=new QuoteCandidate(key+":"+f.stock,f.stock,new BigDecimal(price),LocalDateTime.ofInstant(at,ZoneOffset.UTC),"ALPACA_IEX",null,at,
                "ALPACA","iex",generation+":"+at.atZone(MarketTime.NEW_YORK).toLocalDate(),reason);
        return clock.at(LocalDateTime.ofInstant(at,ZoneOffset.UTC),()->ingestion.ingest(candidate,QuoteIngestionPolicy.LIVE));
    }
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
}
