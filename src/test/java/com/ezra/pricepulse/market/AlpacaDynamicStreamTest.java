package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

class AlpacaDynamicStreamTest {
    final ObjectMapper json=new ObjectMapper();final MarketFeedHealth health=new MarketFeedHealth();
    final List<WebSocket.Listener> listeners=new CopyOnWriteArrayList<>();final List<WebSocket> sockets=new CopyOnWriteArrayList<>();
    final List<String> commands=new CopyOnWriteArrayList<>();final AtomicInteger messages=new AtomicInteger(),gaps=new AtomicInteger();
    final List<Runnable> deadlines=new CopyOnWriteArrayList<>();
    final List<Long> reconnectDelays=new CopyOnWriteArrayList<>();final AtomicLong nanos=new AtomicLong();
    final ScheduledThreadPoolExecutor scheduler=new ScheduledThreadPoolExecutor(2){
        @Override public ScheduledFuture<?> schedule(Runnable work,long delay,TimeUnit unit){
            if(unit.toSeconds(delay)==12){deadlines.add(work);return super.schedule(work,1,TimeUnit.DAYS);}
            reconnectDelays.add(unit.toMillis(delay));
            return super.schedule(work,delay,unit);
        }
    };
    AlpacaStream stream;
    @BeforeEach void start(){
        var properties=new AlpacaProperties();properties.setEnabled(true);properties.setKeyId("fixture");properties.setSecretKey("fixture");properties.setSymbols(List.of("AAPL"));
        stream=new AlpacaStream(properties,json,scheduler,listener->{
            var ws=mock(WebSocket.class);when(ws.sendText(anyString(),eq(true))).thenAnswer(call->{commands.add(call.getArgument(0));return CompletableFuture.completedFuture(ws);});
            when(ws.sendClose(anyInt(),anyString())).thenAnswer(call->{listener.onClose(ws,1000,"");return CompletableFuture.completedFuture(ws);});
            when(ws.sendPing(any())).thenReturn(CompletableFuture.completedFuture(ws));
            listeners.add(listener);sockets.add(ws);listener.onOpen(ws);return CompletableFuture.completedFuture(ws);
        },health,(value,at)->messages.incrementAndGet(),gaps::incrementAndGet,new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.parse("2026-10-30T14:00:00Z").plusNanos(nanos.get());}
        },nanos::get);
        stream.enableDynamicSubscriptions(Map.of("AAPL",0L));stream.start();auth();ack("AAPL");
    }
    @AfterEach void stop(){stream.close();scheduler.shutdownNow();}
    void frame(String value){listeners.getLast().onText(sockets.getLast(),value,true);}
    void auth(){frame("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");}
    void ack(String...symbols){try{frame(json.writeValueAsString(List.of(Map.of("T","subscription","trades",List.of(symbols)))));}catch(Exception e){throw new AssertionError(e);}}
    long sent(String action,String symbol){return commands.stream().filter(s->s.contains("\""+action+"\"")&&s.contains("\""+symbol+"\"")).count();}
    @Test void dynamicAddsShareOneSocketAndDoNotInterruptTheExistingSymbol(){
        stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",1L));stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",1L));
        assertThat(sent("subscribe","PLTR")).isEqualTo(1);assertThat(health.confirms("AAPL")).isTrue();assertThat(health.confirms("PLTR")).isFalse();
        frame("[{\"T\":\"t\",\"S\":\"AAPL\"},{\"T\":\"t\",\"S\":\"PLTR\"}]");assertThat(messages).hasValue(1);
        ack("AAPL","PLTR");frame("[{\"T\":\"t\",\"S\":\"PLTR\"}]");assertThat(messages).hasValue(2);
        assertThat(sockets).hasSize(1);assertThat(gaps).hasValue(0);
    }
    @Test void cancellationWaitsForTheOldSubscribeAckThenUnsubscribesWithoutForwardingItsTrade(){
        stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",1L));stream.desireSubscriptions(Map.of("AAPL",0L));
        assertThat(stream.subscriptions().absent("PLTR")).isFalse();assertThat(sent("unsubscribe","PLTR")).isZero();
        ack("AAPL","PLTR");assertThat(sent("unsubscribe","PLTR")).isEqualTo(1);
        frame("[{\"T\":\"t\",\"S\":\"PLTR\"},{\"T\":\"t\",\"S\":\"AAPL\"}]");assertThat(messages).hasValue(1);
        ack("AAPL");assertThat(stream.subscriptions().absent("PLTR")).isTrue();assertThat(health.confirms("PLTR")).isFalse();assertThat(gaps).hasValue(0);
    }
    @Test void explicitSymbolLimitFailureKeepsExistingSubscriptionsAndAllowsVersionedRetry(){
        stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",1L));frame("[{\"T\":\"error\",\"code\":405,\"msg\":\"private details\"}]");
        assertThat(health.connection()).isEqualTo("SUBSCRIBED");assertThat(health.confirms("AAPL")).isTrue();assertThat(stream.subscriptions().failures().get("PLTR").code()).isEqualTo("UPSTREAM_LIMIT_REACHED");
        stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",2L));assertThat(sent("subscribe","PLTR")).isEqualTo(2);ack("AAPL","PLTR");
        assertThat(stream.subscriptions().confirms("PLTR")).isTrue();assertThat(gaps).hasValue(0);
    }
    @Test void reconnectUsesLatestDemandAndIgnoresOldGenerationAcks()throws Exception{
        var old=listeners.getFirst();var socket=sockets.getFirst();String epoch=stream.subscriptions().epoch();
        stream.desireSubscriptions(Map.of("PLTR",1L));old.onClose(socket,1006,"");assertThat(health.confirms("AAPL")).isFalse();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(sockets.size()<2&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(sockets).hasSize(2);auth();old.onText(socket,"[{\"T\":\"subscription\",\"trades\":[\"AAPL\"]}]",true);
        assertThat(stream.subscriptions().actual()).isEmpty();ack("PLTR");assertThat(stream.subscriptions().epoch()).isNotEqualTo(epoch);
        assertThat(stream.subscriptions().actual()).containsExactly("PLTR");assertThat(gaps).hasValue(1);
    }
    @Test void unknownAckTimeoutInvalidatesTheConnectionAndItsConfirmationProof(){
        stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",1L));deadlines.getLast().run();
        assertThat(health.connection()).isEqualTo("RECONNECTING");assertThat(health.confirms("AAPL")).isFalse();
        assertThat(stream.subscriptions().authenticated()).isFalse();assertThat(stream.subscriptions().absent("PLTR")).isFalse();
        assertThat(health.streamSnapshot().lastFailureCode()).isEqualTo("SUBSCRIPTION_ACK_TIMEOUT");assertThat(gaps).hasValue(1);
    }
    @Test void dynamicAcksDoNotRestartTheHealthyConnectionBackoffWindow()throws Exception{
        listeners.getLast().onClose(sockets.getLast(),1006,"");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(sockets.size()<2&&System.nanoTime()<end)Thread.sleep(10);
        assertThat(sockets).hasSize(2);auth();ack("AAPL");
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(16));stream.desireSubscriptions(Map.of("AAPL",0L,"PLTR",1L));ack("AAPL","PLTR");
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(16));listeners.getLast().onClose(sockets.getLast(),1006,"");
        assertThat(reconnectDelays.getLast()).as("32 healthy seconds reset failure backoff even with a dynamic ACK at second 16").isBetween(1000L,1249L);
    }
}
