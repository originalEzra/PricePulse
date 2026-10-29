package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Explicit opt-in only: not in normal integration includes, no application context or business DB. */
class AlpacaReadOnlySmokeIT {
    @Test void actualJavaClientReadsHistoryAndReconnectsWithBusinessWritesAbsent()throws Exception {
        assertThat(System.getenv("PRICEPULSE_RUN_READONLY_SMOKE")).isEqualTo("true");
        var p=new AlpacaProperties();p.setEnabled(true);
        p.setKeyId(System.getenv("APCA_API_KEY_ID"));p.setSecretKey(System.getenv("APCA_API_SECRET_KEY"));
        p.setEnvironment(Optional.ofNullable(System.getenv("APCA_API_ENV")).orElse("paper"));p.validate();
        var json=new ObjectMapper().findAndRegisterModules();Instant started=Instant.now();
        Map<String,Long> stocks=new LinkedHashMap<>();for(String symbol:p.getSymbols())stocks.put(symbol,(long)stocks.size()+1);
        var result=AlpacaDataClient.authenticated(p,json).download(p,stocks,started.atZone(MarketTime.NEW_YORK).minusMonths(1).toInstant(),started);
        assertThat(result.sessions()).isNotEmpty();assertThat(result.bars()).isNotEmpty();
        for(long id:stocks.values())assertThat(result.bars().stream().filter(b->b.stockId()==id).count()).isPositive();
        for(var bar:result.bars())assertThat(result.sessions().stream().anyMatch(s->s.contains(MarketTime.instant(bar.intervalStart()))
                && !MarketTime.instant(bar.intervalEnd()).isAfter(s.close()))).isTrue();
        var health=new MarketFeedHealth();var scheduler=Executors.newScheduledThreadPool(2);
        var socket=new AtomicReference<WebSocket>();var connections=new AtomicInteger();var gaps=new AtomicInteger();
        var trades=new AtomicInteger();var pongs=new AtomicInteger();var closeCodes=new CopyOnWriteArrayList<Integer>();
        var nativeConnector=AlpacaStream.connector(HttpClient.newHttpClient());
        AlpacaStream.Connector connector=listener->nativeConnector.connect(new WebSocket.Listener(){
            @Override public void onOpen(WebSocket ws){socket.set(ws);connections.incrementAndGet();listener.onOpen(ws);}
            @Override public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){return listener.onText(ws,text,last);}
            @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer bytes){return listener.onPing(ws,bytes);}
            @Override public CompletionStage<?> onPong(WebSocket ws,ByteBuffer bytes){pongs.incrementAndGet();return listener.onPong(ws,bytes);}
            @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){closeCodes.add(code);return listener.onClose(ws,code,reason);}
            @Override public void onError(WebSocket ws,Throwable error){listener.onError(ws,error);}
        });
        try(var stream=new AlpacaStream(p,json,scheduler,connector,health,(message,at)->{
            if(message.path("T").asText().equals("t"))trades.incrementAndGet();
        },gaps::incrementAndGet,Clock.systemUTC())) {
            stream.start();await(()->health.connection().equals("SUBSCRIBED"));
            socket.get().sendPing(ByteBuffer.wrap(new byte[]{1})).get(5,TimeUnit.SECONDS);
            await(()->pongs.get()>0);
            socket.get().sendClose(1000,"read-only reconnect verification").get(5,TimeUnit.SECONDS);
            await(()->connections.get()>=2 && health.connection().equals("SUBSCRIBED"));
            assertThat(gaps.get()).isPositive();
            Thread.sleep(1500); // Short bounded observation, not a throughput measurement.
        }finally{scheduler.shutdownNow();}
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("startedAt",started.toString());report.put("finishedAt",Instant.now().toString());report.put("provider","ALPACA");report.put("feed","iex");
        report.put("businessWritesEnabled",false);report.put("businessDatabaseConnected",false);report.put("historyPages",result.pages());
        report.put("regularSessionBars",result.bars().size());report.put("calendarSessions",result.sessions().size());
        report.put("symbolBarCounts",stocks.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->result.bars().stream().filter(b->b.stockId()==e.getValue()).count())));
        report.put("javaWebSocketConnections",connections.get());report.put("closeCodes",closeCodes);report.put("observedTradeMessages",trades.get());
        report.put("pongResponses",pongs.get());report.put("gracefulCloseAcknowledged",closeCodes.contains(1000));
        report.put("warnings",closeCodes.contains(1000)?List.of():List.of("CONTROLLED_CLOSE_RECEIVED_1006_GRACEFUL_CLOSE_UNVERIFIED"));
        report.put("normalSessionThroughputValidated",false);report.put("realAlertEndToEndValidated",false);
        Path dir=Path.of("target/v2-p2");Files.createDirectories(dir);
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("provider-readonly.json").toFile(),report);
    }
    private void await(java.util.function.BooleanSupplier condition)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("Provider authentication/subscription/reconnect completed").isTrue();
    }
}
