package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;

/** V8 dump/restore and additive V9/V10 rehearsal using only two disposable databases. */
@Testcontainers
class SubscriptionRolloutRehearsalIT {
    @Container static final MySQLContainer<?> SOURCE=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("rollout_source");
    @Container static final MySQLContainer<?> RESTORED=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("rollout_restore");
    final ObjectMapper json=new ObjectMapper();

    @Test @Timeout(120) void restoresVerifiedV8BackupAndUpgradesBothCopiesWithoutLosingOriginalFacts()throws Exception{
        Path directory=Path.of("target/v21-stage4-20261030");Files.createDirectories(directory);
        flyway(SOURCE,"8").migrate();
        try(var c=connection(SOURCE);var s=c.createStatement()){
            s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'backup-one@fixture.test','unused','Synthetic one'),(2,'backup-two@fixture.test','unused','Synthetic two')");
            s.executeUpdate("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,status,version) VALUES(1,1,1,'Preserved snapshot','ABOVE',100,'ONCE','COMPLETED',9)");
            s.executeUpdate("INSERT INTO quote_events(id,event_key,stock_id,price,event_time,source,origin_type,processing_status) VALUES(1,'backup-fixture',1,101,'2026-10-29 14:00:00','SYNTHETIC_FIXTURE','REAL','PROCESSED')");
            s.executeUpdate("INSERT INTO alert_triggers(id,trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot) VALUES(1,'backup-fixture',1,1,101,'2026-10-29 14:00:00','{\"name\":\"Preserved snapshot\"}')");
            s.executeUpdate("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status) VALUES('ALERT_TRIGGER',1,'ALERT_TRIGGERED','{}','SENT')");
            s.executeUpdate("INSERT INTO notifications(user_id,trigger_id,title,content) VALUES(1,1,'Preserved synthetic notification','Preserved')");
            s.executeUpdate("INSERT INTO simulation_runs(id,user_id,stock_id,origin_snapshot,history_prefix_snapshot,parameters,seed,generator_version,calendar_snapshot,sim_clock,sim_clock_ns,latest_price,rule_index_token,start_request_key,start_request_hash) VALUES(UUID(),1,1,'{\"fixture\":\"SYNTHETIC_BACKUP_ONLY\"}','[]','{\"mode\":\"NORMAL\",\"stepRatio\":\"0.003\"}',42,'splitmix64-price-v1','{}','2026-10-29 20:00:00',1793304000000000000,345,UUID(),UUID(),REPEAT('0',64))");
        }
        Map<String,List<String>> columns;
        try(var c=connection(SOURCE)){columns=columns(c);}
        var original=facts(SOURCE,columns);
        var dump=SOURCE.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysqldump -uroot --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF --no-tablespaces \"$MYSQL_DATABASE\"");
        assertThat(dump.getExitCode()).as("disposable V8 dump exit").isZero();
        assertThat(dump.getStdout()).contains("-- Dump completed on ","CREATE TABLE `flyway_schema_history`","CREATE TABLE `simulation_runs`");
        Path sql=directory.resolve("synthetic-v8-rehearsal.sql");Files.writeString(sql,dump.getStdout());
        Files.setPosixFilePermissions(sql,PosixFilePermissions.fromString("rw-------"));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sql)));
        RESTORED.copyFileToContainer(MountableFile.forHostPath(sql),"/tmp/v8-fixture.sql");
        var restored=RESTORED.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql -uroot \"$MYSQL_DATABASE\" < /tmp/v8-fixture.sql");
        assertThat(restored.getExitCode()).as("disposable V8 restore exit").isZero();
        assertThat(facts(RESTORED,columns)).isEqualTo(original);
        assertThat(flyway(RESTORED,"8").validateWithResult().validationSuccessful).isTrue();
        var times=new ArrayList<Map<String,Object>>();
        for(var database:List.of(SOURCE,RESTORED)){
            long began=System.nanoTime();
            assertThat(flyway(database,"9").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(facts(database,columns)).isEqualTo(original);
            assertThat(flyway(database,"10").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(flyway(database,"10").validateWithResult().validationSuccessful).isTrue();
            assertThat(facts(database,columns)).isEqualTo(original);
            try(var c=connection(database);var s=c.createStatement()){
                assertThat(value(s,"SELECT COUNT(*) FROM market_subscription_targets WHERE state='PENDING' AND legacy_hold AND slot_reserved AND confirmed_epoch IS NULL AND intent_version=0")).isEqualTo(15);
                assertThat(value(s,"SELECT COUNT(*) FROM user_stock_subscriptions")).isEqualTo(30);
                assertThat(value(s,"SELECT symbol_limit FROM market_subscription_control WHERE id=1")).isEqualTo(15);
                s.executeUpdate("UPDATE user_stock_subscriptions SET desired=FALSE WHERE user_id=1 AND stock_id=1");
                s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(3,'after-upgrade@fixture.test','unused','New')");
            }
            assertThat(flyway(database,"10").migrate().migrationsExecuted).isZero();
            try(var c=connection(database);var s=c.createStatement()){
                assertThat(value(s,"SELECT desired FROM user_stock_subscriptions WHERE user_id=1 AND stock_id=1")).isZero();
                assertThat(value(s,"SELECT COUNT(*) FROM user_stock_subscriptions WHERE user_id=3")).isZero();
            }
            times.add(Map.of("database",database.getDatabaseName(),"v9ThenV10Ms",(System.nanoTime()-began)/1_000_000.0));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("migration-backup-rehearsal.json").toFile(),Map.of(
                "checkedAt",Instant.now().toString(),"fixture","SYNTHETIC_DISPOSABLE_ONLY","originalV8TablesVerified",columns.size(),
                "backupBytes",Files.size(sql),"backupSha256",hash,"v8BackupRestoredToSeparateDatabase",true,
                "originalFactsPreservedThroughV9AndV10",true,"oneTimeDefaultsAndNoFalseConfirmation",true,
                "migrationTimingsNotProductionDowntimeEstimates",times,"developmentDatabaseConnected",false));
    }
    Flyway flyway(MySQLContainer<?> database,String version){return Flyway.configure().dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword()).locations("classpath:db/migration","classpath:db/local").target(version).load();}
    Connection connection(MySQLContainer<?> database)throws SQLException{return DriverManager.getConnection(database.getJdbcUrl(),database.getUsername(),database.getPassword());}
    long value(Statement s,String sql)throws SQLException{try(var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    Map<String,List<String>> columns(Connection c)throws SQLException{
        var result=new TreeMap<String,List<String>>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position")){
            while(r.next())result.computeIfAbsent(r.getString(1),ignored->new ArrayList<>()).add(r.getString(2));
        }return result;
    }
    Map<String,List<String>> facts(MySQLContainer<?> database,Map<String,List<String>> columns)throws Exception{
        var result=new TreeMap<String,List<String>>();
        try(var c=connection(database);var s=c.createStatement()){
            for(var table:columns.entrySet()){
                var rows=new ArrayList<String>();
                try(var r=s.executeQuery("SELECT "+table.getValue().stream().map(v->"`"+v+"`").collect(java.util.stream.Collectors.joining(","))+" FROM `"+table.getKey()+"`")){
                    while(r.next()){var values=new ArrayList<String>();for(int i=1;i<=table.getValue().size();i++)values.add(r.getString(i));rows.add(json.writeValueAsString(values));}
                }Collections.sort(rows);result.put(table.getKey(),rows);
            }
        }return result;
    }
}
