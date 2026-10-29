package com.ezra.pricepulse.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Explicit temporary browser fixture only. No development DB, provider credentials, or MQ. */
@EnabledIfEnvironmentVariable(named="PRICEPULSE_RUN_SIMULATION_BROWSER",matches="true")
@Testcontainers
class SimulationBrowserHarnessIT {
    @Container static final MySQLContainer<?> MYSQL=SimulationRestartIT.MYSQL;
    @Container static final GenericContainer<?> REDIS=SimulationRestartIT.REDIS;
    @Test @Timeout(1500) void servesClearlyLabelledSyntheticBrowserFixtureUntilReleased() throws Exception {
        Path directory=Path.of(System.getProperty("pricepulse.browser-report-dir","target/v2-p3"));Files.createDirectories(directory);
        boolean chartFixture=Boolean.getBoolean("pricepulse.chart-fixture");
        Path stop=directory.resolve("browser-stop.signal");
        if(Files.exists(stop))throw new IllegalStateException("Prior browser stop signal must be moved aside before starting a new harness");
        var backend=new SimulationRestartIT();Process frontend=null;
        var json=new ObjectMapper().findAndRegisterModules();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        try {
            backend.start(directory.resolve("browser-backend.log"));
            jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES(?,?,?)","simulation-browser@test.local",new BCryptPasswordEncoder().encode("Synthetic-P3-only!"),"合成验收（非实盘）");
            long owner=jdbc.queryForObject("SELECT id FROM users WHERE email='simulation-browser@test.local'",Long.class);
            jdbc.update("UPDATE stocks SET name=CONCAT('合成验收 · ',name) WHERE market='US'");
            var zone=ZoneId.of("America/New_York");Instant now=Instant.now();LocalDate today=now.atZone(zone).toLocalDate();
            var dates=new ArrayList<LocalDate>();
            for(LocalDate day=today.minusDays(35);!day.isAfter(today.plusDays(35));day=day.plusDays(1))
                if(day.getDayOfWeek()!=DayOfWeek.SATURDAY && day.getDayOfWeek()!=DayOfWeek.SUNDAY)dates.add(day);
            for(var day:dates)jdbc.update("INSERT INTO market_sessions(provider,session_date,opens_at,closes_at,fetched_at) VALUES('ALPACA',?,?,?,NOW(6))",day,
                    utc(ZonedDateTime.of(day,LocalTime.of(9,30),zone).toInstant()),utc(ZonedDateTime.of(day,LocalTime.of(16,0),zone).toInstant()));
            for(var stock:jdbc.queryForList("SELECT id FROM stocks WHERE market='US'",Long.class)) {
                var rows=new ArrayList<Object[]>();
                for(var day:dates) {
                    Instant open=ZonedDateTime.of(day,LocalTime.of(9,30),zone).toInstant();
                    if(open.isAfter(now))continue;
                    for(int i=0;i<390;i++) {
                        Instant start=open.plusSeconds(i*60L),end=start.plusSeconds(60);if(end.isAfter(now))break;
                        // Explicit chart fixture: preserve a known five-minute in-session hole for visual checks.
                        if(chartFixture && i>=130 && i<135)continue;
                        BigDecimal price=BigDecimal.valueOf(100+Math.sin(i/12.0)*0.1).setScale(4,RoundingMode.HALF_UP);
                        rows.add(new Object[]{stock,utc(start),utc(end),price});
                    }
                }
                jdbc.batchUpdate("INSERT INTO market_price_bars(stock_id,provider,feed,resolution,adjustment,interval_start,interval_end,open_price,high_price,low_price,close_price,fetched_at) VALUES(?,'ALPACA','iex','1Min','raw',?,?,100,101,99,?,NOW(6))",rows);
                jdbc.update("INSERT INTO market_history_coverage(stock_id,provider,feed,requested_from,requested_to,synced_at) VALUES(?,'ALPACA','iex',?,?,NOW(6))",stock,
                        utc(ZonedDateTime.of(dates.getFirst(),LocalTime.of(9,30),zone).toInstant()),utc(now));
            }
            long aapl=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='AAPL' AND market='US'",Long.class);
            jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,status) VALUES(?,?,'合成上破规则','ABOVE',100.2,'ONCE','ACTIVE')",owner,aapl);
            jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status) VALUES(?,?,'合成重复规则','ABOVE',100.4,'REPEAT',99.8,'COOLDOWN')",owner,aapl);
            int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
            var builder=new ProcessBuilder("node","node_modules/vite/bin/vite.js","--host","127.0.0.1","--port",Integer.toString(port),"--strictPort").directory(Path.of("frontend").toFile());
            builder.environment().put("PRICEPULSE_BACKEND_URL",backend.base);
            frontend=builder.redirectErrorStream(true).redirectOutput(directory.resolve("browser-frontend.log").toFile()).start();
            String url="http://127.0.0.1:"+port;var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();long ready=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean loaded=false;
            while(System.nanoTime()<ready){try{if(http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200){loaded=true;break;}}catch(Exception ignored){}Thread.sleep(100);}
            if(!loaded)throw new AssertionError("Disposable browser frontend did not become ready");
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("browser-harness.json").toFile(),Map.of("url",url,"backendUrl",backend.base,"fixture","SYNTHETIC_NOT_LIVE","developmentDatabaseConnected",false,"providerConnected",false,"formalBusinessWritesEnabled",false));
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);
            while(!Files.exists(stop) && System.nanoTime()<deadline)Thread.sleep(250);
            if(!Files.exists(stop))throw new AssertionError("Browser fixture verification was not released in time");
            for(String table:List.of("quote_events","alert_triggers","outbox_events","notifications","market_stream_state"))
                org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("browser-isolation.json").toFile(),Map.of("checkedAt",Instant.now().toString(),"fixture","SYNTHETIC_NOT_LIVE","formalFactsAdded",0,"developmentDatabaseConnected",false));
        } finally {
            if(frontend!=null){frontend.destroy();if(!frontend.waitFor(5,TimeUnit.SECONDS))frontend.destroyForcibly();}
            backend.stop();
        }
    }
    private LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
}
