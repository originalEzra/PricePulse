package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Real JDK WebSocket frames against localhost, without credentials, provider access or a business database. */
class AlpacaStreamLoopbackTest {
    final ObjectMapper json=new ObjectMapper();
    final MarketFeedHealth health=new MarketFeedHealth();
    final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-29T02:00:00Z"));
    final AtomicLong nanos=new AtomicLong();
    final AtomicInteger gaps=new AtomicInteger(), messages=new AtomicInteger();
    LocalPeer peer;
    AlpacaStream stream;

    @AfterEach void stop() throws Exception {
        if(stream!=null)stream.close();
        if(peer!=null)peer.close();
        scheduler.shutdownNow();
    }
    void start() throws Exception {
        peer=new LocalPeer();
        var properties=new AlpacaProperties();properties.setEnabled(true);
        properties.setKeyId("synthetic-key");properties.setSecretKey("synthetic-secret");properties.setSymbols(List.of("AAPL"));
        var clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return now.get();}
        };
        var http=HttpClient.newHttpClient();
        stream=new AlpacaStream(properties,json,scheduler,
                listener->http.newWebSocketBuilder().buildAsync(peer.uri(),listener),health,
                (message,at)->messages.incrementAndGet(),gaps::incrementAndGet,clock,nanos::get);
        stream.start();await(()->health.connection().equals("SUBSCRIBED"));
    }
    void advance(long seconds){now.set(now.get().plusSeconds(seconds));nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));}
    @Test void realJdkAutomaticallyAnswersPingExactlyOnceAndConfirmsNormalClose() throws Exception {
        start();peer.send(9,new byte[]{7});
        assertThat(peer.pongs.poll(2,TimeUnit.SECONDS)).containsExactly((byte)7);
        assertThat(peer.pongs.poll(200,TimeUnit.MILLISECONDS)).isNull();
        stream.close();
        assertThat(peer.closes.poll(2,TimeUnit.SECONDS)).isEqualTo(1000);
        await(()->Objects.equals(health.streamSnapshot().lastCloseCode(),1000));
        assertThat(health.connection()).isEqualTo("STOPPED");assertThat(gaps).hasValue(0);
        assertThat(health.streamSnapshot().lastFailureCode()).isNull();assertThat(messages).hasValue(0);
    }
    @Test void realPongsKeepQuietConnectionAliveAndMissingPongsStillDeclareAGap() throws Exception {
        start();
        for(int i=1;i<=4;i++) {
            advance(15);stream.heartbeat();
            final int expected=i;await(()->health.streamSnapshot().receivedPongs()==expected);
        }
        assertThat(health.connection()).isEqualTo("SUBSCRIBED");assertThat(gaps).hasValue(0);
        peer.replyPong=false;
        for(int i=0;i<3;i++){advance(15);stream.heartbeat();}
        advance(1);stream.heartbeat();
        assertThat(health.connection()).isEqualTo("RECONNECTING");assertThat(gaps).hasValue(1);
        assertThat(health.streamSnapshot().heartbeatTimeouts()).isEqualTo(1);
        assertThat(health.streamSnapshot().lastFailureCode()).isEqualTo("HEARTBEAT_TIMEOUT");
    }
    @Test void serverInitiatedCloseGetsReciprocalCloseAndClientReauthenticates() throws Exception {
        start();peer.send(8,new byte[]{3,(byte)232});
        assertThat(peer.closes.poll(2,TimeUnit.SECONDS)).isEqualTo(1000);
        await(()->health.streamSnapshot().subscriptions()==2 && health.connection().equals("SUBSCRIBED"));
        assertThat(gaps).hasValue(1);assertThat(health.streamSnapshot().connectionAttempts()).isEqualTo(2);
        stream.close();assertThat(peer.closes.poll(2,TimeUnit.SECONDS)).isEqualTo(1000);
    }
    @Test void runtimeShutdownWaitsForPeerAcknowledgementBeforeStoppingItsScheduler() throws Exception {
        start();peer.closeReply=new CountDownLatch(1);
        var runtime=new RealMarketRuntime(null,null,null,null,null,health,json,Clock.systemUTC());
        ReflectionTestUtils.setField(runtime,"stream",stream);
        ReflectionTestUtils.setField(runtime,"scheduler",scheduler);
        var stopping=CompletableFuture.runAsync(runtime::stop);
        try {
            assertThat(peer.closes.poll(2,TimeUnit.SECONDS)).isEqualTo(1000);
            assertThat(stopping).as("application shutdown must still await the delayed peer Close").isNotDone();
            assertThat(scheduler.isShutdown()).isFalse();
            peer.closeReply.countDown();stopping.get(2,TimeUnit.SECONDS);
            assertThat(health.streamSnapshot().lastCloseCode()).isEqualTo(1000);
            assertThat(health.streamSnapshot().lastFailureCode()).isNull();
            assertThat(scheduler.isShutdown()).isTrue();assertThat(gaps).hasValue(0);
        } finally { peer.closeReply.countDown();stopping.get(4,TimeUnit.SECONDS); }
    }
    @Test void resumedRealSocketCannotForwardATradeBeforeThePauseIsDeclared() throws Exception {
        start();advance(90);
        peer.send(1,"[{\"T\":\"t\"}]".getBytes(StandardCharsets.UTF_8));
        await(()->gaps.get()==1);
        assertThat(messages).hasValue(0);
        assertThat(health.streamSnapshot().lastFailureCode()).isEqualTo("CLIENT_TIMING_GAP");
        assertThat(health.streamSnapshot().heartbeatTimeouts()).isZero();
    }
    @Test void runtimeShutdownIsBoundedWhenPeerNeverAcknowledges() throws Exception {
        start();peer.closeReply=new CountDownLatch(1);
        var runtime=new RealMarketRuntime(null,null,null,null,null,health,json,Clock.systemUTC());
        ReflectionTestUtils.setField(runtime,"stream",stream);
        ReflectionTestUtils.setField(runtime,"scheduler",scheduler);
        long began=System.nanoTime();
        try {
            runtime.stop();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)).isBetween(2500L,4500L);
            assertThat(scheduler.isShutdown()).isTrue();assertThat(gaps).hasValue(0);
            assertThat(health.streamSnapshot().lastCloseCode()).isNull();
            assertThat(health.streamSnapshot().lastFailureCode()).isEqualTo("STOP_CLOSE_TIMEOUT_OR_ERROR");
        } finally { peer.closeReply.countDown(); }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as("bounded localhost WebSocket condition").isTrue();
    }

    static final class LocalPeer implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers=Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        final List<Socket> sockets=new CopyOnWriteArrayList<>();
        final BlockingQueue<byte[]> pongs=new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closes=new LinkedBlockingQueue<>();
        final Set<OutputStream> closeSent=ConcurrentHashMap.newKeySet();
        volatile OutputStream output;
        volatile boolean replyPong=true,stopped;
        volatile CountDownLatch closeReply=new CountDownLatch(0);
        LocalPeer() throws IOException {
            workers.submit(()->{
                while(!stopped)try {
                    var socket=server.accept();sockets.add(socket);workers.submit(()->serve(socket));
                }catch(IOException ignored){if(!stopped)throw new UncheckedIOException(ignored);}
            });
        }
        URI uri(){return URI.create("ws://127.0.0.1:"+server.getLocalPort()+"/local-only");}
        void serve(Socket socket) {
            try(socket) {
                socket.setSoTimeout(8000);
                var input=socket.getInputStream();var out=socket.getOutputStream();
                var header=new ByteArrayOutputStream();
                while(header.size()<8192) {
                    int b=input.read();if(b<0)throw new EOFException();header.write(b);
                    if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;
                }
                String key=Arrays.stream(header.toString(StandardCharsets.US_ASCII).split("\r\n"))
                        .filter(line->line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                        .map(line->line.substring(line.indexOf(':')+1).trim()).findFirst().orElseThrow();
                String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
                output=out;
                while(!stopped) {
                    int first=input.read();if(first<0)return;
                    int second=input.read();if(second<0)throw new EOFException();
                    int length=second&127;
                    if(length==126)length=(input.read()<<8)|input.read();
                    if(length>8192 || (second&128)==0)throw new IOException("Invalid local client frame");
                    byte[] mask=input.readNBytes(4),payload=input.readNBytes(length);
                    if(mask.length!=4 || payload.length!=length)throw new EOFException();
                    for(int i=0;i<payload.length;i++)payload[i]^=mask[i%4];
                    int opcode=first&15;
                    if(opcode==1) {
                        String text=new String(payload,StandardCharsets.UTF_8);
                        if(text.contains("\"auth\""))send(out,1,"[{\"T\":\"success\",\"msg\":\"authenticated\"}]".getBytes(StandardCharsets.UTF_8));
                        else if(text.contains("\"subscribe\""))send(out,1,"[{\"T\":\"subscription\",\"trades\":[\"AAPL\"]}]".getBytes(StandardCharsets.UTF_8));
                    } else if(opcode==9 && replyPong)send(out,10,payload);
                    else if(opcode==10)pongs.add(payload);
                    else if(opcode==8) {
                        closes.add(payload.length<2?1005:((payload[0]&255)<<8)|(payload[1]&255));
                        closeReply.await(5,TimeUnit.SECONDS);
                        if(!closeSent.contains(out))send(out,8,payload);
                        return;
                    }
                }
            }catch(Exception ignored){/* A timeout/abort is intentional in the missing-Pong case. */}
        }
        void send(int opcode,byte[] payload) throws IOException {send(output,opcode,payload);}
        void send(OutputStream out,int opcode,byte[] payload) throws IOException {
            synchronized(out) {
                if(opcode==8)closeSent.add(out);
                out.write(128|opcode);
                if(payload.length<126)out.write(payload.length);
                else {out.write(126);out.write(payload.length>>8);out.write(payload.length&255);}
                out.write(payload);out.flush();
            }
        }
        @Override public void close() throws IOException {
            stopped=true;server.close();
            for(var socket:sockets)socket.close();
            workers.shutdownNow();
        }
    }
}
