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

/** Opt-in online observation. No Spring context, JDBC, Redis, rules, Outbox or MQ. */
class AlpacaOpenSessionReadOnlyIT {
    record Sample(int connection, String symbol, String eventKey, String price, Instant eventTime, Instant receivedAt) {}
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final List<Sample> samples = new CopyOnWriteArrayList<>();
    final Map<String,Long> rawCounts = new TreeMap<>(), conditionCounts = new TreeMap<>(), rejected = new TreeMap<>();
    final Map<Long,Long> rawSeconds = new TreeMap<>(), eligibleSeconds = new TreeMap<>();
    final List<Double> delays = new ArrayList<>(), parsingMicros = new ArrayList<>();
    final List<Map<String,Object>> closes = new CopyOnWriteArrayList<>();
    final List<Map<String,Object>> subscriptions = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger(), gaps = new AtomicInteger(), pongs = new AtomicInteger();
    final AtomicBoolean observing = new AtomicBoolean();
    final AtomicReference<WebSocket> socket = new AtomicReference<>();
    final List<Map<String,Object>> windows = new ArrayList<>();
    List<MarketDataModels.Session> sessions;
    Set<String> symbols;

    @Test void observesNormalSessionAndReconnectWithoutBusinessWrites() throws Exception {
        assertThat(System.getenv("PRICEPULSE_RUN_READONLY_SMOKE")).isEqualTo("true");
        int seconds = Integer.parseInt(Optional.ofNullable(System.getenv("PRICEPULSE_SAMPLE_SECONDS")).orElse("120"));
        assertThat(seconds).isBetween(30,300);
        var properties = new AlpacaProperties(); properties.setEnabled(true);
        properties.setKeyId(Optional.ofNullable(System.getenv("APCA_API_KEY_ID")).orElse(""));
        properties.setSecretKey(Optional.ofNullable(System.getenv("APCA_API_SECRET_KEY")).orElse(""));
        properties.setEnvironment(Optional.ofNullable(System.getenv("APCA_API_ENV")).orElse("paper")); properties.validate();
        symbols = Set.copyOf(properties.getSymbols());
        Map<String,Long> stocks = new LinkedHashMap<>();
        properties.getSymbols().forEach(s -> stocks.put(s,(long)stocks.size()+1));
        Instant started = Instant.now();
        // Only a recent ten-minute slice, not another download of the already verified month.
        var recent = AlpacaDataClient.authenticated(properties,json).download(properties,stocks,started.minusSeconds(600),started);
        sessions = recent.sessions();
        assertThat(sessions).anyMatch(s -> s.contains(started) && s.contains(started.plusSeconds(seconds*2L+90)));
        var health = new MarketFeedHealth(); var scheduler = Executors.newScheduledThreadPool(2);
        var nativeConnector = AlpacaStream.connector(HttpClient.newHttpClient());
        AlpacaStream.Connector connector = listener -> nativeConnector.connect(new WebSocket.Listener() {
            @Override public void onOpen(WebSocket ws) { socket.set(ws); connections.incrementAndGet(); listener.onOpen(ws); }
            @Override public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last) {
                String before = health.connection();
                var done = listener.onText(ws,text,last);
                if (!before.equals("SUBSCRIBED") && health.connection().equals("SUBSCRIBED"))
                    subscriptions.add(Map.of("connection",connections.get(),"at",Instant.now().toString()));
                return done;
            }
            @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer data) { return listener.onPing(ws,data); }
            @Override public CompletionStage<?> onPong(WebSocket ws,ByteBuffer data) { pongs.incrementAndGet(); return listener.onPong(ws,data); }
            @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason) {
                closes.add(Map.of("connection",connections.get(),"code",code,"at",Instant.now().toString()));
                return listener.onClose(ws,code,reason);
            }
            @Override public void onError(WebSocket ws,Throwable error) { listener.onError(ws,error); }
        });
        var stream = new AlpacaStream(properties,json,scheduler,connector,health,this::observe,gaps::incrementAndGet,Clock.systemUTC());
        boolean complete = false;
        try {
            stream.start(); await(() -> health.connection().equals("SUBSCRIBED"));
            socket.get().sendPing(ByteBuffer.wrap(new byte[]{1})).get(5,TimeUnit.SECONDS);
            await(() -> pongs.get()>0);
            observeWindow(seconds);
            int before = connections.get();
            socket.get().sendClose(1000,"read-only reconnect verification").get(5,TimeUnit.SECONDS);
            await(() -> connections.get()>before && health.connection().equals("SUBSCRIBED"));
            observeWindow(seconds);
            complete = true;
        } finally {
            observing.set(false);
            stream.close();
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while (socket.get()!=null && !socket.get().isInputClosed() && System.nanoTime()<deadline) Thread.sleep(50);
            scheduler.shutdownNow();
            writeReport(started,recent,health,complete);
        }
        assertThat(samples).as("Actual protocol-eligible regular-session trades received").isNotEmpty();
        for (String symbol : symbols) assertThat(samples).anyMatch(s -> s.symbol().equals(symbol));
        assertThat(subscriptions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(gaps.get()).isPositive(); assertThat(pongs.get()).isPositive();
    }

    private void observeWindow(int seconds) throws InterruptedException {
        Instant from = Instant.now(); int first = samples.size(); observing.set(true);
        Thread.sleep(seconds*1000L);
        observing.set(false);
        windows.add(Map.of("connection",connections.get(),"from",from.toString(),"to",Instant.now().toString(),
                "eligibleTrades",samples.size()-first));
    }
    private synchronized void observe(com.fasterxml.jackson.databind.JsonNode item,Instant received) {
        if (!observing.get()) return;
        if (!"t".equals(item.path("T").asText())) { rejected.merge("CORRECTION_OR_CANCEL",1L,Long::sum); return; }
        rawCounts.merge(item.path("S").asText(),1L,Long::sum);
        rawSeconds.merge(received.getEpochSecond(),1L,Long::sum);
        List<String> conditions = new ArrayList<>(); item.path("c").forEach(c -> conditions.add(c.asText()));
        Collections.sort(conditions); conditionCounts.merge(String.join(",",conditions),1L,Long::sum);
        long parsingStart = System.nanoTime();
        try {
            var trade = AlpacaProtocol.trade(item,received,symbols);
            if (trade==null) { rejected.merge("SALE_CONDITION",1L,Long::sum); return; }
            if (sessions.stream().noneMatch(s -> s.contains(trade.eventTime()))) { rejected.merge("OUTSIDE_RTH",1L,Long::sum); return; }
            samples.add(new Sample(connections.get(),trade.symbol(),trade.eventKey(),trade.price().toPlainString(),trade.eventTime(),received));
            eligibleSeconds.merge(received.getEpochSecond(),1L,Long::sum);
            delays.add(Duration.between(trade.eventTime(),received).toNanos()/1_000_000.0);
        } catch (RuntimeException ignored) { rejected.merge("INVALID_OR_UNSUPPORTED_TRADE",1L,Long::sum); }
        finally { parsingMicros.add((System.nanoTime()-parsingStart)/1000.0); }
    }
    private synchronized void writeReport(Instant started,AlpacaDataClient.Download recent,MarketFeedHealth health,boolean complete) throws Exception {
        var report = new LinkedHashMap<String,Object>();
        report.put("startedAt",started); report.put("finishedAt",Instant.now()); report.put("provider","ALPACA"); report.put("feed","iex");
        report.put("businessWritesEnabled",false); report.put("businessDatabaseConnected",false);
        report.put("recentHistoryPages",recent.pages()); report.put("recentClosedBars",recent.bars().size());
        report.put("windows",windows); report.put("rawTradeCounts",rawCounts); report.put("saleConditionCounts",conditionCounts);
        report.put("rejected",rejected); report.put("eligibleTrades",samples.size());
        report.put("eligibleBySymbol",symbols.stream().collect(java.util.stream.Collectors.toMap(s -> s,s -> samples.stream().filter(t -> t.symbol().equals(s)).count())));
        double duration = windows.stream().mapToDouble(w -> Duration.between(Instant.parse(w.get("from").toString()),Instant.parse(w.get("to").toString())).toNanos()/1e9).sum();
        report.put("observationSeconds",duration); report.put("rawTradesPerSecond",duration==0?0:rawCounts.values().stream().mapToLong(Long::longValue).sum()/duration);
        report.put("eligibleTradesPerSecond",duration==0?0:samples.size()/duration);
        report.put("peakRawTradesPerUtcSecond",rawSeconds.values().stream().mapToLong(Long::longValue).max().orElse(0));
        report.put("peakEligibleTradesPerUtcSecond",eligibleSeconds.values().stream().mapToLong(Long::longValue).max().orElse(0));
        report.put("eventToReceivedMs",percentiles(delays)); report.put("protocolCallbackMicros",percentiles(parsingMicros));
        report.put("negativeLatencySamples",delays.stream().filter(d -> d<0).count());
        report.put("subMicrosecondTimestamps",samples.stream().filter(s -> s.eventTime().getNano()%1000!=0).count());
        report.put("subscriptions",subscriptions); report.put("connections",connections.get()); report.put("gapCallbacks",gaps.get()); report.put("pongResponses",pongs.get());
        report.put("closeObservations",closes); report.put("lastSafeError",health.snapshot("AAPL",true,Instant.now(),null,false).lastError());
        report.put("gracefulCloseAcknowledged",closes.size()==connections.get() && closes.stream().allMatch(c -> c.get("code").equals(1000)));
        report.put("normalSessionReceptionObserved",complete && !samples.isEmpty());
        report.put("databaseCapacityValidated",false); report.put("realAlertEndToEndValidated",false);
        Path directory = Path.of("target/v2-p2"); Files.createDirectories(directory);
        String prefix=Optional.ofNullable(System.getenv("PRICEPULSE_SAMPLE_REPORT_PREFIX")).orElse("open-session");
        if(!prefix.matches("[a-z0-9-]{1,40}"))throw new IllegalArgumentException("Invalid read-only report prefix");
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(prefix+"-readonly.json").toFile(),report);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(prefix+"-trades.json").toFile(),samples);
    }
    static Map<String,Object> percentiles(List<Double> values) {
        if (values.isEmpty()) return Map.of("count",0);
        var sorted = values.stream().sorted().toList(); var result = new LinkedHashMap<String,Object>(); result.put("count",sorted.size());
        for (int percentile : List.of(50,95,99,100)) result.put("p"+percentile,sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*percentile/100.0)-1)));
        return result;
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("Read-only provider subscription/pong/reconnect completed").isTrue();
    }
}
