package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class OpeningGapMigrationInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("opening_migration");

    @Test void existingV7RuleTimesStatesAndImmutableTriggersSurviveTheAdditiveMigration() throws Exception {
        var configuration=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration");
        configuration.target("7").load().migrate();
        try(var connection=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var sql=connection.createStatement()){
            sql.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'fixture@opening.test','test','Fixture')");
            sql.executeUpdate("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,status,version,created_at,updated_at) VALUES(1,1,1,'Existing','ABOVE',105,'ONCE','COMPLETED',1,'2026-10-28 12:00:00.123456','2026-10-28 14:00:00.654321')");
            sql.executeUpdate("INSERT INTO quote_events(id,event_key,stock_id,price,event_time,source,origin_type,processing_status) VALUES(1,'existing',1,110,'2026-10-28 14:00:00','ALPACA_IEX','REAL','PROCESSED')");
            sql.executeUpdate("INSERT INTO alert_triggers(id,trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot) VALUES(1,'existing-trigger',1,1,110,'2026-10-28 14:00:00','{\"name\":\"Immutable\"}')");
            sql.executeUpdate("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status) VALUES('ALERT_TRIGGER',1,'ALERT_TRIGGERED','{}','SENT')");
            sql.executeUpdate("INSERT INTO notifications(user_id,trigger_id,title,content) VALUES(1,1,'Existing','Immutable')");
        }
        var flyway=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var connection=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var sql=connection.createStatement()){
            assertThat(value(sql,"SELECT effective_at FROM alert_rules WHERE id=1")).isEqualTo("2026-10-28 14:00:00.654321");
            assertThat(value(sql,"SELECT updated_at FROM alert_rules WHERE id=1")).isEqualTo("2026-10-28 14:00:00.654321");
            assertThat(value(sql,"SELECT created_at FROM alert_rules WHERE id=1")).isEqualTo("2026-10-28 12:00:00.123456");
            assertThat(value(sql,"SELECT status FROM alert_rules WHERE id=1")).isEqualTo("COMPLETED");
            assertThat(value(sql,"SELECT version FROM alert_rules WHERE id=1")).isEqualTo("1");
            assertThat(value(sql,"SELECT JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.name')) FROM alert_triggers WHERE id=1")).isEqualTo("Immutable");
            assertThat(value(sql,"SELECT trigger_context IS NULL FROM alert_triggers WHERE id=1")).isEqualTo("1");
            assertThat(value(sql,"SELECT status FROM outbox_events WHERE aggregate_id=1")).isEqualTo("SENT");
            assertThat(value(sql,"SELECT COUNT(*) FROM notifications")).isEqualTo("1");
        }
    }
    private String value(Statement sql,String query)throws SQLException{try(var result=sql.executeQuery(query)){result.next();return result.getString(1);}}
}
