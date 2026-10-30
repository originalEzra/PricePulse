package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Opening-gap recovery across two actual application processes; all resources are disposable. */
@Testcontainers
class SimulationGapRestartIT {
    @Container static final MySQLContainer<?> MYSQL=SimulationRestartIT.MYSQL;
    @Container static final GenericContainer<?> REDIS=SimulationRestartIT.REDIS;

    @Test @Timeout(180) void recoversDiscreteOpeningAndRetryWithoutDependingOnLiveCalendarOrRedis()throws Exception{
        var backend=new SimulationRestartIT();
        Path directory=Path.of("target/v21-stage3-20261030");Files.createDirectories(directory);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        try{
            backend.start(directory.resolve("gap-restart-first.log"));
            long stock=jdbc.queryForObject("SELECT id FROM stocks WHERE symbol='AAPL' AND market='US'",Long.class);
            jdbc.update("INSERT INTO users(email,password_hash,display_name) VALUES('gap-restart@test.local','unused','Gap restart fixture')");
            long owner=jdbc.queryForObject("SELECT id FROM users WHERE email='gap-restart@test.local'",Long.class);
            backend.token=backend.token(owner);
            // Thanksgiving is absent; Friday has an early close; Monday is the following session.
            for(String date:List.of("2024-11-26","2024-11-27","2024-11-29","2024-12-02"))
                jdbc.update("INSERT INTO market_sessions(provider,session_date,opens_at,closes_at,fetched_at) VALUES('ALPACA',?,?,?,NOW(6))",
                        date,date+" 14:30:00",date+(date.equals("2024-11-29")?" 18:00:00":" 21:00:00"));
            for(String date:List.of("2024-11-26","2024-11-27"))
                jdbc.update("INSERT INTO market_price_bars(stock_id,provider,feed,resolution,adjustment,interval_start,interval_end,open_price,high_price,low_price,close_price,fetched_at) VALUES(?,'ALPACA','iex','1Min','raw',?,?,345,345,345,345,NOW(6))",
                        stock,date+" 20:59:00",date+" 21:00:00");
            jdbc.update("INSERT INTO market_history_coverage(stock_id,provider,feed,requested_from,requested_to,synced_at) VALUES(?,'ALPACA','iex','2024-11-26 14:30:00','2024-11-27 21:00:00',NOW(6))",stock);
            long bar=jdbc.queryForObject("SELECT id FROM market_price_bars WHERE stock_id=? AND interval_end='2024-11-27 21:00:00'",Long.class,stock);
            jdbc.update("INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,status) VALUES(?,?,'Gap restart snapshot','ABOVE',350,'ONCE','ACTIVE')",owner,stock);
            var realRules=jdbc.queryForList("SELECT id,status,version,effective_at FROM alert_rules ORDER BY id");
            var realPrices=jdbc.queryForList("SELECT id,latest_price,latest_quote_at,rule_index_token FROM stocks ORDER BY id");
            var body=backend.json.createObjectNode();body.put("stockId",stock);
            body.putObject("origin").put("kind","HISTORICAL_POINT").put("pointRef","bar:"+bar).put("revision","1");
            body.put("mode","OPENING_GAP").put("stepRatio","0.003").put("seed",42).putNull("expectedCurrentRunId");
            body.putObject("openingGap").put("source","REAL_HISTORY").put("openingPrice","355").put("continuationMode","FALL");
            var run=backend.call("POST","/api/simulations/start",body,UUID.randomUUID().toString());String id=run.required("runId").asText();
            String key=UUID.randomUUID().toString();var advance=backend.json.valueToTree(Map.of("expectedVersion",0,"expectedBatchNo",0));
            var opening=backend.call("POST","/api/simulations/"+id+"/advance",advance,key);
            assertThat(opening.required("points").size()).isEqualTo(1);assertThat(opening.required("triggered").asInt()).isEqualTo(1);
            assertThat(opening.required("toClock").asText()).isEqualTo("2024-11-29T14:30:00Z");
            var saved=backend.call("GET","/api/simulations/current",null,null);
            var timeline=backend.call("GET","/api/simulations/"+id+"/timeline",null,null);
            var events=backend.call("GET","/api/simulations/"+id+"/events?from=2024-11-27T21:00:00Z&to=2024-11-29T14:30:00Z",null,null);
            assertThat(events.required("events").get(0).required("triggerContext").required("referencePrice").asText()).isEqualTo("345.0000");
            backend.stop();
            REDIS.execInContainer("redis-cli","FLUSHDB");
            jdbc.update("DELETE FROM market_sessions");
            jdbc.update("UPDATE market_price_bars SET close_price=346,high_price=346,revision=revision+1 WHERE id=?",bar);
            backend.start(directory.resolve("gap-restart-second.log"));
            assertThat(backend.call("GET","/api/simulations/current",null,null)).isEqualTo(saved);
            assertThat(backend.call("GET","/api/simulations/"+id+"/timeline",null,null)).isEqualTo(timeline);
            assertThat(backend.call("GET","/api/simulations/"+id+"/events?from=2024-11-27T21:00:00Z&to=2024-11-29T14:30:00Z",null,null)).isEqualTo(events);
            assertThat(backend.call("POST","/api/simulations/"+id+"/advance",advance,key)).isEqualTo(opening);
            var hour=backend.call("POST","/api/simulations/"+id+"/advance",backend.json.valueToTree(Map.of("expectedVersion",1,"expectedBatchNo",1)),UUID.randomUUID().toString());
            assertThat(hour.required("points").size()).isEqualTo(60);assertThat(hour.required("firstPointSeq").asInt()).isEqualTo(2);assertThat(hour.required("lastPointSeq").asInt()).isEqualTo(61);
            assertThat(hour.required("toClock").asText()).isEqualTo("2024-11-29T15:30:00Z");assertThat(hour.required("triggered").asInt()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulation_steps",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulation_rule_events",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT id,status,version,effective_at FROM alert_rules ORDER BY id")).isEqualTo(realRules);
            assertThat(jdbc.queryForList("SELECT id,latest_price,latest_quote_at,rule_index_token FROM stocks ORDER BY id")).isEqualTo(realPrices);
            for(String table:List.of("quote_events","alert_triggers","outbox_events","notifications","market_stream_state"))
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
            backend.json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("gap-process-restart.json").toFile(),Map.of(
                    "checkedAt",Instant.now().toString(),"fixture","SYNTHETIC_DISPOSABLE_NOT_LIVE","applicationProcesses",2,
                    "calendarDeletedAndHistoryRevised",true,"redisClearedBetweenProcesses",true,"frozenRunTimelineAndEventsRestored",true,
                    "openingRetryReturnedOriginalOnePointBatch",true,"committedBatches",2,"totalSimulationPoints",61,"formalFactsAdded",0));
        }finally{backend.stop();}
    }
}
