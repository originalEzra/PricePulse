package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class AlpacaStreamTest {
    final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);
    final ObjectMapper json=new ObjectMapper();
    final MarketFeedHealth health=new MarketFeedHealth();
    final List<WebSocket.Listener> listeners=new CopyOnWriteArrayList<>();
    final List<WebSocket> sockets=new CopyOnWriteArrayList<>();
    final AtomicInteger gaps=new AtomicInteger(), messages=new AtomicInteger();
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-28T14:00:00Z"));
    AlpacaStream stream;
    @AfterEach void close(){if(stream!=null)stream.close();scheduler.shutdownNow();}
    void start(){
        var properties=new AlpacaProperties();properties.setEnabled(true);properties.setKeyId("dummy-key");properties.setSecretKey("dummy-secret");properties.setSymbols(List.of("AAPL"));
        var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        stream=new AlpacaStream(properties,json,scheduler,listener->{
            var ws=mock(WebSocket.class);
            when(ws.sendText(anyString(),anyBoolean())).thenReturn(CompletableFuture.completedFuture(ws));
            when(ws.sendPing(any())).thenReturn(CompletableFuture.completedFuture(ws));
            when(ws.sendPong(any())).thenReturn(CompletableFuture.completedFuture(ws));
            when(ws.sendClose(anyInt(),anyString())).thenReturn(CompletableFuture.completedFuture(ws));
            sockets.add(ws);listeners.add(listener);listener.onOpen(ws);return CompletableFuture.completedFuture(ws);
        },health,(message,at)->messages.incrementAndGet(),gaps::incrementAndGet,clock);
        stream.start();
    }
    void frame(String text){listeners.getLast().onText(sockets.getLast(),text,true);}
    void subscribe(){frame("[{\"T\":\"success\",\"msg\":\"authenticated\"}]");frame("[{\"T\":\"subscription\",\"trades\":[\"AAPL\"]}]");}
    @Test void authenticatesSubscribesAndParsesFragmentedBatchesWithoutForwardingQuotes() {
        start();subscribe();
        assertThat(health.connection()).isEqualTo("SUBSCRIBED");
        listeners.getFirst().onText(sockets.getFirst(),"[{\"T\":\"t\"},",false);
        listeners.getFirst().onText(sockets.getFirst(),"{\"T\":\"q\"}]",true);
        assertThat(messages).hasValue(1);
        verify(sockets.getFirst()).sendText(contains("auth"),eq(true));
        verify(sockets.getFirst()).sendText(contains("subscribe"),eq(true));
    }
    @Test void reconnectsResubscribesAndIgnoresLateCallbacksFromOldSocket() throws Exception {
        start();subscribe();var old=listeners.getFirst();var ws=sockets.getFirst();
        old.onClose(ws,1006,"not logged");
        assertThat(gaps).hasValue(1);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(listeners.size()<2 && System.nanoTime()<deadline)Thread.sleep(20);
        assertThat(listeners).hasSize(2);subscribe();
        old.onClose(ws,1006,"late");
        assertThat(health.connection()).isEqualTo("SUBSCRIBED");assertThat(gaps).hasValue(1);
        stream.close();verify(sockets.getLast()).sendClose(eq(1000),eq("shutdown"));
        assertThat(health.connection()).isEqualTo("STOPPED");
    }
    @Test void permissionFailureIsTerminalAndProviderTextIsNotExposed() {
        start();frame("[{\"T\":\"error\",\"code\":403,\"msg\":\"private provider text\"}]");
        assertThat(health.connection()).isEqualTo("REJECTED");
        assertThat(health.snapshot("AAPL",true,now.get(),null,false).lastError()).isEqualTo("PROVIDER_ERROR_403");
        assertThat(gaps).hasValue(1);
    }
    @Test void heartbeatAndOversizedFramesDeclareGapRatherThanClaimingHealthyConnection() {
        start();subscribe();now.set(now.get().plusSeconds(46));stream.heartbeat();
        assertThat(health.connection()).isEqualTo("RECONNECTING");assertThat(gaps).hasValue(1);
    }
    @Test void subscriptionMismatchIsRejectedBeforeTradesAreForwarded() {
        start();frame("[{\"T\":\"subscription\",\"trades\":[\"MSFT\"]}]");
        frame("[{\"T\":\"t\"}]");assertThat(messages).hasValue(0);
        assertThat(health.connection()).isEqualTo("REJECTED");
    }
    @Test void orderlyStopDrainsInputAndWaitsForPeerCloseWithoutForwardingTrades() {
        start();subscribe();var ws=sockets.getFirst();var listener=listeners.getFirst();
        stream.close();
        verify(ws).request(Long.MAX_VALUE);
        verify(ws,never()).abort(); // A completed sendClose is not the peer's acknowledgement.
        listener.onText(ws,"[{\"T\":\"t\"}]",true);
        listener.onClose(ws,1000,"not logged");
        assertThat(messages).hasValue(0);assertThat(gaps).hasValue(0);
        assertThat(health.connection()).isEqualTo("STOPPED");verify(ws,never()).abort();
    }
    @Test void unsolicitedNormalPeerCloseStillDeclaresGapButAllowsReciprocalClose() {
        start();subscribe();var ws=sockets.getFirst();
        listeners.getFirst().onClose(ws,1000,"not logged");
        assertThat(gaps).hasValue(1);assertThat(health.connection()).isEqualTo("RECONNECTING");
        verify(ws,never()).abort();
    }
    @Test void abnormalCloseOnStopIsNotReportedAsNormalAndDoesNotReconnect() {
        start();subscribe();var ws=sockets.getFirst();stream.close();
        listeners.getFirst().onClose(ws,1006,"not logged");
        assertThat(health.snapshot("AAPL",true,now.get(),null,false).lastError()).isEqualTo("STOP_CLOSE_1006");
        assertThat(health.connection()).isEqualTo("STOPPED");assertThat(gaps).hasValue(0);
    }
    @Test void flushedCloseWithoutPeerAcknowledgementIsAbortedAfterBoundedTimeout() throws Exception {
        start();subscribe();var ws=sockets.getFirst();stream.close();
        verify(ws,never()).abort();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(health.snapshot("AAPL",true,now.get(),null,false).lastError()==null && System.nanoTime()<deadline)Thread.sleep(20);
        verify(ws).abort();
        assertThat(health.snapshot("AAPL",true,now.get(),null,false).lastError()).isEqualTo("STOP_CLOSE_TIMEOUT_OR_ERROR");
        assertThat(gaps).hasValue(0);
    }
}
