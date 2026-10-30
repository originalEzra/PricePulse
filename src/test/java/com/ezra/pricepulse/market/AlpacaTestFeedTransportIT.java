package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in: the supplier's always-open FAKEPACA test endpoint, not the occupied IEX endpoint. */
@EnabledIfEnvironmentVariable(named="PRICEPULSE_RUN_TEST_TRANSPORT",matches="true")
class AlpacaTestFeedTransportIT {
    @Test void nativeJdkTransportReceivesPongsAndReportsActualPeerClose() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String key=System.getenv("APCA_API_KEY_ID"),secret=System.getenv("APCA_API_SECRET_KEY");
        assertThat(key).as("configured backend key").isNotBlank();assertThat(secret).as("configured backend secret").isNotBlank();
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        var pongs=new AtomicInteger();var points=new AtomicInteger();var subscribed=new CompletableFuture<Void>();
        var closed=new CompletableFuture<Integer>();var ioErrors=new AtomicInteger();var pingErrors=new AtomicInteger();
        Instant began=Instant.now();WebSocket ws=null;Integer status=null;
        var report=new LinkedHashMap<String,Object>();
        try {
            ws=HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create("wss://stream.data.alpaca.markets/v2/test"),new WebSocket.Listener() {
                        final StringBuilder buffer=new StringBuilder();
                        @Override public void onOpen(WebSocket socket) {
                            socket.request(1);
                            try {socket.sendText(json.writeValueAsString(Map.of("action","auth","key",key,"secret",secret)),true);}
                            catch(Exception ignored){subscribed.completeExceptionally(new IllegalStateException("AUTH_ENCODING_FAILED"));}
                        }
                        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence part,boolean last) {
                            buffer.append(part);
                            if(buffer.length()>1_048_576){socket.abort();subscribed.completeExceptionally(new IllegalStateException("FRAME_LIMIT"));return null;}
                            if(last)try {
                                for(var item:json.readTree(buffer.toString())) {
                                    String type=item.path("T").asText();
                                    if(type.equals("success")&&item.path("msg").asText().equals("authenticated"))
                                        socket.sendText("{\"action\":\"subscribe\",\"trades\":[\"FAKEPACA\"]}",true);
                                    else if(type.equals("subscription")&&item.path("trades").toString().contains("FAKEPACA"))subscribed.complete(null);
                                    else if(type.equals("t"))points.incrementAndGet();
                                    else if(type.equals("error"))subscribed.completeExceptionally(new IllegalStateException("PROVIDER_CODE_"+item.path("code").asInt()));
                                }
                            }catch(Exception ignored){subscribed.completeExceptionally(new IllegalStateException("INVALID_TEST_FEED"));}
                            finally {if(last)buffer.setLength(0);}
                            socket.request(1);return null;
                        }
                        @Override public CompletionStage<?> onPong(WebSocket socket,ByteBuffer data) {
                            pongs.incrementAndGet();socket.request(1);return null;
                        }
                        @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason) {closed.complete(code);return null;}
                        @Override public void onError(WebSocket socket,Throwable error) {
                            ioErrors.incrementAndGet();closed.completeExceptionally(new IllegalStateException("TEST_FEED_IO_ERROR"));
                            subscribed.completeExceptionally(new IllegalStateException("TEST_FEED_IO_ERROR"));
                        }
                    }).get(15,TimeUnit.SECONDS);
            subscribed.get(15,TimeUnit.SECONDS);
            WebSocket connected=ws;
            scheduler.scheduleWithFixedDelay(()->connected.sendPing(ByteBuffer.wrap(new byte[]{1}))
                    .exceptionally(error->{pingErrors.incrementAndGet();return null;}),0,15,TimeUnit.SECONDS);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
            while(System.nanoTime()<end&&!closed.isDone())Thread.sleep(100);
        } finally {
            scheduler.shutdownNow();
            if(ws!=null) {
                ws.request(Long.MAX_VALUE);
                try {ws.sendClose(1000,"readonly transport check").get(5,TimeUnit.SECONDS);status=closed.get(5,TimeUnit.SECONDS);}
                catch(Exception ignored){ws.abort();}
            }
            report.put("checkedAt",Instant.now());report.put("durationSeconds",Duration.between(began,Instant.now()).toSeconds());
            report.put("endpoint","wss://stream.data.alpaca.markets/v2/test");report.put("symbol","FAKEPACA");
            report.put("subscriptionAcknowledged",subscribed.isDone()&&!subscribed.isCompletedExceptionally());
            report.put("pongResponses",pongs.get());report.put("testTradeMessages",points.get());
            report.put("ioErrors",ioErrors.get());report.put("pingErrors",pingErrors.get());report.put("peerCloseCode",status);
            report.put("normalCloseConfirmed",Objects.equals(status,1000));report.put("iexConnectionOpened",false);
            report.put("businessDatabaseConnected",false);report.put("formalBusinessWritesEnabled",false);report.put("realMarketAcceptance",false);
            var folder=Path.of("target/v2-p2-offhours-20261029");Files.createDirectories(folder);
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("provider-test-transport.json").toFile(),report);
        }
        assertThat(pongs.get()).as("test endpoint returned real protocol Pongs").isPositive();
        assertThat(pingErrors.get()).isZero();assertThat(ioErrors.get()).isZero();
        assertThat(status).as("actual peer close, never remap 1006").isEqualTo(1000);
    }
}
