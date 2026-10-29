package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real packaged-JAR process restart over disposable DB/Redis and explicitly synthetic market fixtures. */
@Testcontainers
class SimulationRestartIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("simulation_restart").withUrlParam("rewriteBatchedStatements","true");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);
    static final String SECRET="restart-test-only-secret-at-least-32-bytes";
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final HttpClient http=HttpClient.newHttpClient();
    String base,token;
    Process process;

    @Test @Timeout(180) void restartsPackagedApplicationAndRecoversFrozenInputsClockRulesAndIdempotentBatch() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(source);Path directory=Path.of("target/v2-p3");Files.createDirectories(directory);
        try {
            start(directory.resolve("restart-first.log"));
            long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='AAPL' AND market='US'",Long.class);
            jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES('restart@test.local','unused','Restart fixture')");
            long owner=jdbc.queryForObject("SELECT id FROM users WHERE email='restart@test.local'",Long.class);
            token=token(owner);
            for(String date:List.of("2024-10-28","2024-10-29","2024-10-30"))
                jdbc.update("INSERT INTO market_sessions(provider,session_date,opens_at,closes_at,fetched_at) VALUES('ALPACA',?,?,?,NOW(6))",date,date+" 13:30:00",date+" 20:00:00");
            var rows=new ArrayList<Object[]>();
            Instant origin=Instant.parse("2024-10-29T13:31:00Z"),previous=Instant.parse("2024-10-28T13:30:00Z");
            for(int i=0;i<390;i++)rows.add(new Object[]{stock,utc(previous.plusSeconds(i*60L)),utc(previous.plusSeconds((i+1)*60L))});
            rows.add(new Object[]{stock,utc(origin.minusSeconds(60)),utc(origin)});
            jdbc.batchUpdate("INSERT INTO market_price_bars(stock_id,provider,feed,resolution,adjustment,interval_start,interval_end,open_price,high_price,low_price,close_price,fetched_at) VALUES(?,'ALPACA','iex','1Min','raw',?,?,100,100,100,100,NOW(6))",rows);
            jdbc.update("INSERT INTO market_history_coverage(stock_id,provider,feed,requested_from,requested_to,synced_at) VALUES(?,'ALPACA','iex',?,?,NOW(6))",stock,utc(previous),utc(origin.plusSeconds(3600)));
            long bar=jdbc.queryForObject("SELECT id FROM market_price_bars WHERE stock_id=? AND interval_end=?",Long.class,stock,utc(origin));
            jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,status) VALUES(?,?,'Restart snapshot','ABOVE',100.0001,'ONCE','ACTIVE')",owner,stock);
            var body=json.createObjectNode();body.put("stockId",stock);body.putObject("origin").put("kind","HISTORICAL_POINT").put("pointRef","bar:"+bar).put("revision","1");
            body.put("mode","RISE").put("stepRatio","0.003").put("seed",42).putNull("expectedCurrentRunId");
            var run=call("POST","/api/simulations/start",body,UUID.randomUUID().toString());String id=run.path("runId").asText();
            String command=UUID.randomUUID().toString();var advance=json.valueToTree(Map.of("expectedVersion",0,"expectedBatchNo",0));
            var first=call("POST","/api/simulations/"+id+"/advance",advance,command);
            assertThat(first.path("points").size()).isEqualTo(60);assertThat(first.path("triggered").asInt()).isEqualTo(1);
            var saved=call("GET","/api/simulations/current",null,null);
            var before=call("GET","/api/simulations/"+id+"/timeline",null,null);
            jdbc.update("UPDATE market_price_bars SET close_price=101,high_price=101,revision=revision+1 WHERE id=?",bar);
            jdbc.update("UPDATE alert_rules SET name='new real config',threshold_price=999,version=version+1 WHERE user_id=?",owner);
            stop();
            REDIS.execInContainer("redis-cli","FLUSHDB");
            start(directory.resolve("restart-second.log"));
            assertThat(call("GET","/api/simulations/current",null,null)).isEqualTo(saved);
            assertThat(call("GET","/api/simulations/"+id+"/timeline",null,null)).isEqualTo(before);
            assertThat(call("POST","/api/simulations/"+id+"/advance",advance,command)).isEqualTo(first);
            var second=call("POST","/api/simulations/"+id+"/advance",json.valueToTree(Map.of("expectedVersion",1,"expectedBatchNo",1)),UUID.randomUUID().toString());
            assertThat(second.path("versionAfter").asLong()).isEqualTo(2);
            var times=java.util.stream.IntStream.rangeClosed(1,120).mapToObj(i->origin.plusSeconds(i*60L)).toList();
            var expected=SimulationGenerator.generate(SimulationGenerator.VERSION,42,1,new BigDecimal("100.0000"),SimulationGenerator.Mode.RISE,new BigDecimal("0.003"),times);
            assertThat(second.path("points").get(0).path("price").asText()).isEqualTo(expected.get(60).price().toPlainString());
            assertThat(second.path("points").get(59).path("price").asText()).isEqualTo(expected.get(119).price().toPlainString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulation_steps",Long.class)).isEqualTo(2);
            for(String table:List.of("quote_events","alert_triggers","outbox_events","notifications","market_stream_state"))assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("process-restart.json").toFile(),Map.of(
                    "checkedAt",Instant.now().toString(),"applicationProcesses",2,"previousProcessExitedBeforeRestart",true,
                    "frozenInputsAndSnapshotsRestored",true,"redisClearedBetweenProcesses",true,"retryReturnedOriginalBatch",true,
                    "committedBatches",2,"formalBusinessWritesEnabled",false,"marketData","SYNTHETIC_DISPOSABLE_FIXTURE","formalFactsAdded",0));
        } finally {stop();}
    }
    void start(Path log) throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}base="http://127.0.0.1:"+port;
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",Path.of("target/price-pulse-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),
                "--spring.profiles.active=integration","--server.port="+port,"--price-pulse.alpaca.enabled=false","--price-pulse.alpaca.business-writes-enabled=false",
                "--price-pulse.messaging.enabled=false","--price-pulse.assistant.enabled=false");
        var env=builder.environment();env.put("SPRING_DATASOURCE_URL",MYSQL.getJdbcUrl());env.put("SPRING_DATASOURCE_USERNAME",MYSQL.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",MYSQL.getPassword());
        env.put("SPRING_DATA_REDIS_HOST",REDIS.getHost());env.put("SPRING_DATA_REDIS_PORT",REDIS.getMappedPort(6379).toString());env.put("JWT_SECRET",SECRET);
        process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(System.nanoTime()<deadline && process.isAlive()) {
            try{var response=http.send(HttpRequest.newBuilder(URI.create(base+"/actuator/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()==200 && json.readTree(response.body()).path("status").asText().equals("UP"))return;
            }catch(Exception ignored){}Thread.sleep(100);
        }
        throw new AssertionError("Disposable restart process did not become healthy; inspect target/v2-p3 restart log");
    }
    void stop() throws Exception {
        if(process!=null && process.isAlive()){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new AssertionError("Disposable process did not stop gracefully");}}
        process=null;
    }
    private JsonNode call(String method,String path,JsonNode body,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token);
        if(key!=null)request.header("Idempotency-Key",key);
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Disposable simulation API "+path).isEqualTo(200);
        return json.readTree(response.body()).required("data");
    }
    private String token(long owner)throws Exception {
        long now=Instant.now().getEpochSecond();String header=base64(json.writeValueAsBytes(Map.of("alg","HS256","typ","JWT")));
        String payload=base64(json.writeValueAsBytes(Map.of("iss","price-pulse","sub",Long.toString(owner),"iat",now,"exp",now+600)));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return header+"."+payload+"."+base64(mac.doFinal((header+"."+payload).getBytes(StandardCharsets.UTF_8)));
    }
    private String base64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private LocalDateTime utc(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
}
