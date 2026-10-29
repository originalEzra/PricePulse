package com.ezra.pricepulse.market;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One connection, authenticated resubscription, bounded frames, ping/pong and jittered backoff. */
public final class AlpacaStream implements AutoCloseable {
    public interface Connector { CompletableFuture<WebSocket> connect(WebSocket.Listener listener); }
    private final AlpacaProperties properties;
    private final ObjectMapper json;
    private final ScheduledExecutorService scheduler;
    private final Connector connector;
    private final MarketFeedHealth health;
    private final BiConsumer<JsonNode,Instant> message;
    private final Runnable gap;
    private final Clock clock;
    private volatile boolean running;
    private volatile WebSocket socket;
    private volatile CompletableFuture<Integer> peerClosed=CompletableFuture.completedFuture(WebSocket.NORMAL_CLOSURE);
    private volatile long lastFrame, lastPong, generation;
    private volatile boolean subscribed;
    private volatile long subscribedAt;
    private int failures;
    private ScheduledFuture<?> reconnect, heartbeat;
    public AlpacaStream(AlpacaProperties properties,ObjectMapper json,ScheduledExecutorService scheduler,
            Connector connector,MarketFeedHealth health,BiConsumer<JsonNode,Instant> message,Runnable gap,Clock clock) {
        this.properties=properties;this.json=json;this.scheduler=scheduler;this.connector=connector;
        this.health=health;this.message=message;this.gap=gap;this.clock=clock;
    }
    public static Connector connector(HttpClient http) {
        return listener -> http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create("wss://stream.data.alpaca.markets/v2/iex"),listener);
    }
    public synchronized void start() {
        if (running) return;
        properties.validate(); running=true; connect();
        heartbeat=scheduler.scheduleWithFixedDelay(this::heartbeat,15,15,TimeUnit.SECONDS);
    }
    private synchronized void connect() {
        if (!running) return;
        long attempt=++generation;
        subscribed=false; health.connection("CONNECTING");
        lastFrame=clock.millis(); lastPong=lastFrame;
        var listener=new Listener(attempt); peerClosed=listener.closed;
        connector.connect(listener).whenComplete((ws,error)-> {
            if (error!=null) fail(attempt,"CONNECT_FAILED",false);
            else if (!running || generation!=attempt) ws.abort();
        });
        scheduler.schedule(()-> { if (running && generation==attempt && !subscribed) fail(attempt,"HANDSHAKE_TIMEOUT",false); },12,TimeUnit.SECONDS);
    }
    private synchronized void fail(long attempt,String code,boolean terminal) {
        if (!running || generation!=attempt) return;
        if (subscribed && clock.millis()-subscribedAt>=30000) failures=0;
        generation++; subscribed=false;
        WebSocket ws=socket; socket=null; if (ws!=null) ws.abort();
        health.error(code); health.connection(terminal?"REJECTED":"RECONNECTING"); gap.run();
        if (terminal) { running=false; if (heartbeat!=null) heartbeat.cancel(false); return; }
        long delay=Math.min(60,1L<<Math.min(failures++,6));
        long millis=delay*1000+ThreadLocalRandom.current().nextLong(250);
        reconnect=scheduler.schedule(this::connect,millis,TimeUnit.MILLISECONDS);
    }
    void heartbeat() {
        WebSocket ws=socket; if (!running || ws==null) return;
        long attempt=generation;
        if (clock.millis()-Math.max(lastFrame,lastPong)>45000) { fail(attempt,"HEARTBEAT_TIMEOUT",false); return; }
        ws.sendPing(ByteBuffer.wrap(new byte[]{1})).exceptionally(error->{fail(attempt,"PING_FAILED",false);return null;});
    }
    private final class Listener implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder fragments=new StringBuilder();
        private final CompletableFuture<Integer> closed=new CompletableFuture<>();
        private Listener(long attempt) { this.attempt=attempt; }
        @Override public void onOpen(WebSocket ws) {
            if (!running || generation!=attempt) { ws.abort();return; }
            socket=ws; ws.request(1);
            try {
                ws.sendText(json.writeValueAsString(Map.of("action","auth","key",properties.getKeyId(),"secret",properties.getSecretKey())),true)
                    .exceptionally(e->{fail(attempt,"AUTH_SEND_FAILED",false);return null;});
            } catch (Exception ignored) { fail(attempt,"AUTH_ENCODE_FAILED",true); }
        }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            if (generation!=attempt || !running) return null;
            if (fragments.length()+data.length()>1_048_576) { fail(attempt,"FRAME_TOO_LARGE",false);return null; }
            fragments.append(data);
            if (last) {
                lastFrame=clock.millis();
                try {
                    JsonNode batch=json.readTree(fragments.toString()); fragments.setLength(0);
                    if (!batch.isArray()) throw new IllegalArgumentException();
                    for (JsonNode item:batch) {
                        String type=item.path("T").asText();
                        if (type.equals("success") && item.path("msg").asText().equals("authenticated")) {
                            ws.sendText(json.writeValueAsString(Map.of("action","subscribe","trades",properties.getSymbols())),true)
                                .exceptionally(e->{fail(attempt,"SUBSCRIBE_SEND_FAILED",false);return null;});
                        } else if (type.equals("subscription")) {
                            Set<String> actual=new HashSet<>(); item.path("trades").forEach(s->actual.add(s.asText()));
                            if (!actual.equals(new HashSet<>(properties.getSymbols()))) { fail(attempt,"SUBSCRIPTION_MISMATCH",true);break; }
                            subscribed=true; subscribedAt=clock.millis();health.connection("SUBSCRIBED");
                        } else if (type.equals("error")) {
                            int code=item.path("code").asInt();
                            fail(attempt,"PROVIDER_ERROR_"+code,Set.of(400,401,402,403,405,409).contains(code));break;
                        } else if (subscribed && Set.of("t","c","x").contains(type)) message.accept(item,clock.instant());
                    }
                } catch (Exception ignored) { fragments.setLength(0);fail(attempt,"INVALID_STREAM_FRAME",false); }
            }
            ws.request(1);return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer data) {
            lastFrame=clock.millis(); ws.request(1);return ws.sendPong(data);
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer bytes,boolean last) {
            fail(attempt,"UNSUPPORTED_BINARY_FRAME",false);return null;
        }
        @Override public CompletionStage<?> onPong(WebSocket ws,ByteBuffer data) {
            lastPong=clock.millis();ws.request(1);return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason) {
            closed.complete(status);
            synchronized (AlpacaStream.this) {
                if (running && generation==attempt) {
                    // Input is already closed. Let the JDK send its reciprocal Close, not abort it.
                    socket=null; fail(attempt,"STREAM_CLOSED_"+status,false);
                }
            }
            return null;
        }
        @Override public void onError(WebSocket ws,Throwable error) {
            closed.completeExceptionally(new IllegalStateException("STREAM_IO_ERROR"));
            fail(attempt,"STREAM_IO_ERROR",false);
        }
    }
    @Override public synchronized void close() {
        running=false;generation++;subscribed=false;
        if (reconnect!=null) reconnect.cancel(false);
        if (heartbeat!=null) heartbeat.cancel(false);
        WebSocket ws=socket;socket=null;
        if (ws!=null) {
            long stoppedGeneration=generation;
            var acknowledgement=peerClosed;
            // Draining frames after stop must not forward trades, but must still reach onClose.
            ws.request(Long.MAX_VALUE);
            ws.sendClose(WebSocket.NORMAL_CLOSURE,"shutdown").thenCompose(ignored->acknowledgement)
                .orTimeout(3,TimeUnit.SECONDS).whenComplete((status,error)->{
                    if (error!=null) {
                        ws.abort();
                        if (!running && generation==stoppedGeneration) health.error("STOP_CLOSE_TIMEOUT_OR_ERROR");
                    } else if (status!=WebSocket.NORMAL_CLOSURE && !running && generation==stoppedGeneration)
                        health.error("STOP_CLOSE_"+status);
                });
        }
        health.connection("STOPPED");
    }
}
