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
    private static final long SILENCE_NANOS=TimeUnit.SECONDS.toNanos(45);
    private static final long CLOCK_DRIFT_MILLIS=5000;
    private final AlpacaProperties properties;
    private final ObjectMapper json;
    private final ScheduledExecutorService scheduler;
    private final Connector connector;
    private final MarketFeedHealth health;
    private final BiConsumer<JsonNode,Instant> message;
    private final Runnable gap;
    private final Clock clock;
    private final LongSupplier ticker;
    private volatile boolean running;
    private volatile WebSocket socket;
    private volatile CompletableFuture<Integer> peerClosed=CompletableFuture.completedFuture(WebSocket.NORMAL_CLOSURE);
    private long lastReceivedNanos, lastHeartbeatNanos, lastHeartbeatMillis;
    private volatile long generation;
    private volatile boolean subscribed;
    private long subscribedAtNanos;
    private CompletableFuture<Void> closeCompletion=CompletableFuture.completedFuture(null);
    private WebSocket closingSocket;
    private long closingGeneration;
    private int failures;
    private ScheduledFuture<?> reconnect, heartbeat;
    public AlpacaStream(AlpacaProperties properties,ObjectMapper json,ScheduledExecutorService scheduler,
            Connector connector,MarketFeedHealth health,BiConsumer<JsonNode,Instant> message,Runnable gap,Clock clock) {
        this(properties,json,scheduler,connector,health,message,gap,clock,System::nanoTime);
    }
    AlpacaStream(AlpacaProperties properties,ObjectMapper json,ScheduledExecutorService scheduler,
            Connector connector,MarketFeedHealth health,BiConsumer<JsonNode,Instant> message,Runnable gap,Clock clock,LongSupplier ticker) {
        this.properties=properties;this.json=json;this.scheduler=scheduler;this.connector=connector;
        this.health=health;this.message=message;this.gap=gap;this.clock=clock;this.ticker=ticker;
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
        health.streamAttempt(clock.instant());
        lastReceivedNanos=ticker.getAsLong();lastHeartbeatNanos=lastReceivedNanos;lastHeartbeatMillis=clock.millis();
        var listener=new Listener(attempt); peerClosed=listener.closed;
        connector.connect(listener).whenComplete((ws,error)-> {
            if (error!=null) fail(attempt,"CONNECT_FAILED",false);
            else if (!running || generation!=attempt) ws.abort();
        });
        scheduler.schedule(()-> { if (running && generation==attempt && !subscribed) fail(attempt,"HANDSHAKE_TIMEOUT",false); },12,TimeUnit.SECONDS);
    }
    private synchronized void fail(long attempt,String code,boolean terminal) {
        if (!running || generation!=attempt) return;
        long now=ticker.getAsLong();
        if (subscribed && now-subscribedAtNanos>=TimeUnit.SECONDS.toNanos(30)) failures=0;
        generation++; subscribed=false;
        WebSocket ws=socket; socket=null; if (ws!=null) ws.abort();
        health.streamFailure(code,clock.instant(),elapsedMillis(now,lastHeartbeatNanos),elapsedMillis(now,lastReceivedNanos),clockDrift(now));
        health.connection(terminal?"REJECTED":"RECONNECTING"); gap.run();
        if (terminal) { running=false; if (heartbeat!=null) heartbeat.cancel(false); return; }
        long delay=Math.min(60,1L<<Math.min(failures++,6));
        long millis=delay*1000+ThreadLocalRandom.current().nextLong(250);
        reconnect=scheduler.schedule(this::connect,millis,TimeUnit.MILLISECONDS);
    }
    synchronized void heartbeat() {
        WebSocket ws=socket; if (!running || ws==null) return;
        long attempt=generation;
        if (!checkClientTiming(attempt)) return;
        long now=ticker.getAsLong();
        if (now-lastReceivedNanos>SILENCE_NANOS) { fail(attempt,"HEARTBEAT_TIMEOUT",false); return; }
        lastHeartbeatNanos=now;lastHeartbeatMillis=clock.millis();
        health.streamPing(clock.instant());
        ws.sendPing(ByteBuffer.wrap(new byte[]{1})).exceptionally(error->{fail(attempt,"PING_FAILED",false);return null;});
    }
    private long elapsedMillis(long now,long since) { return TimeUnit.NANOSECONDS.toMillis(now-since); }
    private long clockDrift(long now) { return clock.millis()-lastHeartbeatMillis-elapsedMillis(now,lastHeartbeatNanos); }
    private boolean checkClientTiming(long attempt) {
        long now=ticker.getAsLong(),drift=clockDrift(now);
        // A paused scheduler or a wall-clock discontinuity is not evidence of a silent provider.
        // Check before accepting a frame too: a buffered Pong/trade after resume must not conceal a gap.
        if (now-lastHeartbeatNanos>SILENCE_NANOS || drift>CLOCK_DRIFT_MILLIS || drift< -CLOCK_DRIFT_MILLIS) {
            fail(attempt,"CLIENT_TIMING_GAP",false);return false;
        }
        return true;
    }
    private final class Listener implements WebSocket.Listener {
        private final long attempt;
        private final StringBuilder fragments=new StringBuilder();
        private final CompletableFuture<Integer> closed=new CompletableFuture<>();
        private Listener(long attempt) { this.attempt=attempt; }
        @Override public void onOpen(WebSocket ws) {
            synchronized (AlpacaStream.this) {
                if (!running || generation!=attempt) { ws.abort();return; }
                socket=ws; ws.request(1);
                try {
                    ws.sendText(json.writeValueAsString(Map.of("action","auth","key",properties.getKeyId(),"secret",properties.getSecretKey())),true)
                        .exceptionally(e->{fail(attempt,"AUTH_SEND_FAILED",false);return null;});
                } catch (Exception ignored) { fail(attempt,"AUTH_ENCODE_FAILED",true); }
            }
        }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            synchronized (AlpacaStream.this) {
                if (generation!=attempt || !running) return null;
                if (!checkClientTiming(attempt)) return null;
                if (fragments.length()+data.length()>1_048_576) { fail(attempt,"FRAME_TOO_LARGE",false);return null; }
                lastReceivedNanos=ticker.getAsLong(); health.streamFrame(clock.instant());
                fragments.append(data);
                if (last) {
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
                                if (!subscribed) health.streamSubscribed(clock.instant());
                                subscribed=true; subscribedAtNanos=ticker.getAsLong();health.connection("SUBSCRIBED");
                            } else if (type.equals("error")) {
                                int code=item.path("code").asInt();
                                fail(attempt,"PROVIDER_ERROR_"+code,Set.of(400,401,402,403,405,409).contains(code));break;
                            } else if (subscribed && Set.of("t","c","x").contains(type)) message.accept(item,clock.instant());
                        }
                    } catch (Exception ignored) { fragments.setLength(0);fail(attempt,"INVALID_STREAM_FRAME",false); }
                }
                ws.request(1);return null;
            }
        }
        @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer data) {
            synchronized (AlpacaStream.this) {
                if (!running || generation!=attempt) return null;
                if (!checkClientTiming(attempt)) return null;
                lastReceivedNanos=ticker.getAsLong(); health.streamFrame(clock.instant());
                // The JDK automatically replies to Ping. An explicit sendPong queues a duplicate.
                ws.request(1); return null;
            }
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer bytes,boolean last) {
            fail(attempt,"UNSUPPORTED_BINARY_FRAME",false);return null;
        }
        @Override public CompletionStage<?> onPong(WebSocket ws,ByteBuffer data) {
            synchronized (AlpacaStream.this) {
                if (!running || generation!=attempt) return null;
                if (!checkClientTiming(attempt)) return null;
                lastReceivedNanos=ticker.getAsLong(); health.streamPong(clock.instant());
                ws.request(1);return null;
            }
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason) {
            synchronized (AlpacaStream.this) {
                // Ignore an old generation's close in the current diagnostic summary, but settle its stop future.
                if (generation==attempt || peerClosed==closed) health.streamClosed(status,clock.instant());
                closed.complete(status);
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
        if (!running && socket==null && health.connection().equals("STOPPED")) return;
        running=false;generation++;subscribed=false;
        if (reconnect!=null) reconnect.cancel(false);
        if (heartbeat!=null) heartbeat.cancel(false);
        WebSocket ws=socket;socket=null;
        if (ws!=null) {
            long stoppedGeneration=generation;
            var acknowledgement=peerClosed;
            var completion=new CompletableFuture<Void>();closeCompletion=completion;
            closingSocket=ws;closingGeneration=stoppedGeneration;health.streamCloseRequested(clock.instant());
            // Draining frames after stop must not forward trades, but must still reach onClose.
            try {
                ws.request(Long.MAX_VALUE);
                ws.sendClose(WebSocket.NORMAL_CLOSURE,"shutdown").thenCompose(ignored->{
                    if (!running && generation==stoppedGeneration) health.streamCloseSent(clock.instant());
                    return acknowledgement;
                }).orTimeout(3,TimeUnit.SECONDS).whenComplete((status,error)->finishStop(ws,stoppedGeneration,completion,
                        error!=null?"STOP_CLOSE_TIMEOUT_OR_ERROR":status!=WebSocket.NORMAL_CLOSURE?"STOP_CLOSE_"+status:null,error!=null));
            } catch (RuntimeException ignored) {
                finishStop(ws,stoppedGeneration,completion,"STOP_CLOSE_TIMEOUT_OR_ERROR",true);
            }
        }
        health.connection("STOPPED");
    }
    private synchronized void finishStop(WebSocket ws,long attempt,CompletableFuture<Void> completion,String failure,boolean abort) {
        if (completion.isDone()) return;
        if (abort) ws.abort();
        if (failure!=null && !running && generation==attempt) health.streamFailure(failure,clock.instant());
        if (closingSocket==ws) closingSocket=null;
        completion.complete(null);
    }
    /** Application shutdown must keep the JVM alive for the bounded peer handshake, without holding the listener lock. */
    public void closeAndAwait() {
        CompletableFuture<Void> completion;WebSocket ws;long attempt;
        synchronized (this) { close();completion=closeCompletion;ws=closingSocket;attempt=closingGeneration; }
        try { completion.get(4,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            if (ws!=null) finishStop(ws,attempt,completion,"STOP_CLOSE_WAIT_INTERRUPTED",true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            if (ws!=null) finishStop(ws,attempt,completion,"STOP_CLOSE_TIMEOUT_OR_ERROR",true);
        }
    }
}
