package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in transport-only check, sequentially replacing the stopped local stream. No Spring/JDBC/Redis/MQ. */
@EnabledIfEnvironmentVariable(named="PRICEPULSE_RUN_IEX_TRANSPORT",matches="true")
class AlpacaIexTransportReadOnlyIT {
    @Test void fixedIexProductionClientSustainsHeartbeatAndReportsActualClose() throws Exception {
        // Refuse to occupy another IEX session while the configured local backend is running.
        boolean backendListening;
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",18081),500);backendListening=true;
        }catch(java.io.IOException expected){backendListening=false;}
        assertThat(backendListening).as("stop the original local IEX owner before this opt-in check").isFalse();
        var properties=new AlpacaProperties();properties.setEnabled(true);properties.setBusinessWritesEnabled(false);
        properties.setKeyId(Optional.ofNullable(System.getenv("APCA_API_KEY_ID")).orElse(""));
        properties.setSecretKey(Optional.ofNullable(System.getenv("APCA_API_SECRET_KEY")).orElse(""));
        properties.setEnvironment(Optional.ofNullable(System.getenv("APCA_API_ENV")).orElse("paper"));properties.validate();
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var health=new MarketFeedHealth();var gaps=new java.util.concurrent.atomic.AtomicInteger();
        var scheduler=Executors.newScheduledThreadPool(2);
        var stream=new AlpacaStream(properties,json,scheduler,AlpacaStream.connector(HttpClient.newHttpClient()),health,
                (message,received)->{
                    var trade=AlpacaProtocol.trade(message,received,Set.copyOf(properties.getSymbols()));
                    if(trade!=null)health.observed(trade.symbol(),trade.eventTime());else health.reject();
                },gaps::incrementAndGet,Clock.systemUTC());
        Instant began=Instant.now();
        boolean subscribed=false;
        try {
            stream.start();
            long handshake=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!health.connection().equals("SUBSCRIBED")&&!health.connection().equals("REJECTED")&&System.nanoTime()<handshake)Thread.sleep(50);
            subscribed=health.connection().equals("SUBSCRIBED");
            if(subscribed) {
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
                while(System.nanoTime()<until&&!health.connection().equals("REJECTED"))Thread.sleep(100);
            }
        } finally {
            stream.closeAndAwait();
            scheduler.shutdownNow();
            var report=new LinkedHashMap<String,Object>();
            report.put("beganAt",began);report.put("finishedAt",Instant.now());report.put("endpoint","wss://stream.data.alpaca.markets/v2/iex");
            report.put("subscribed",subscribed);report.put("symbols",properties.getSymbols());report.put("stream",health.streamSnapshot());
            report.put("gaps",gaps.get());report.put("businessDatabaseConnected",false);report.put("formalBusinessWritesEnabled",false);
            report.put("realRegularSessionAcceptance",false);report.put("originalBackendWasStopped",!backendListening);
            var folder=Path.of("target/iex-transport");Files.createDirectories(folder);
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("iex-transport-"+began.toEpochMilli()+".json").toFile(),report);
        }
        assertThat(subscribed).isTrue();assertThat(health.streamSnapshot().subscriptions()).isEqualTo(1);
        assertThat(health.streamSnapshot().connectionAttempts()).isEqualTo(1);
        assertThat(health.streamSnapshot().receivedPongs()).isGreaterThanOrEqualTo(3);
        assertThat(health.streamSnapshot().heartbeatTimeouts()).isZero();
        assertThat(health.streamSnapshot().lastCloseCode()).as("actual IEX peer close, never remap 1006").isEqualTo(1000);
        assertThat(health.streamSnapshot().lastFailureCode()).isNull();assertThat(gaps).hasValue(0);
    }
}
