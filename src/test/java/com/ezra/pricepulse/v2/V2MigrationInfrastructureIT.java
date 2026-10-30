package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class V2MigrationInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4.7").withDatabaseName("v2_upgrade");

    @Test void upgradesV3WithoutDeletingOrRelabellingLegacyFactsAndQuarantinesOldPendingMessages() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("3").load().migrate();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users(email,password_hash,display_name) VALUES('legacy@example.com','unused','Legacy')");
            s.executeUpdate("""
                    INSERT INTO stocks(symbol,name,market,currency,latest_price,latest_quote_at)
                    VALUES('LEGACY','Legacy stock','SIM','USD',101,'2024-06-01 10:00:00')
                    """);
            s.executeUpdate("INSERT INTO stocks(symbol,name,market,currency,latest_price) VALUES('AAPL','Existing Apple','US','USD',42)");
            s.executeUpdate("""
                    INSERT INTO quote_events(event_key,stock_id,price,event_time,source,processing_status)
                    VALUES('legacy-event',1,101,'2024-06-01 10:00:00','SIMULATION','PROCESSED')
                    """);
            s.executeUpdate("""
                    INSERT INTO alert_rules(user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status,version)
                    VALUES(1,1,'Legacy rule','ABOVE',100,'REPEAT',95,'COOLDOWN',1)
                    """);
            s.executeUpdate("""
                    INSERT INTO alert_triggers(trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot)
                    VALUES('legacy-trigger',1,1,101,'2024-06-01 10:00:00','{"stockId":1,"direction":"ABOVE","thresholdPrice":100}')
                    """);
            for (String status : new String[]{"PENDING", "SENDING", "FAILED", "SENT"}) {
                s.executeUpdate("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status,attempts,locked_by)"
                        + " VALUES('ALERT_TRIGGER',1,'ALERT_TRIGGERED','{}','" + status + "',2,'old-worker')");
            }
            s.executeUpdate("INSERT INTO notifications(user_id,trigger_id,title,content) VALUES(1,1,'Old notification','Retained')");
        }
        Flyway flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("10");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement()) {
            assertThat(value(s, "SELECT origin_type FROM quote_events WHERE id=1")).isEqualTo("LEGACY");
            assertThat(value(s, "SELECT latest_price FROM stocks WHERE id=1")).isEqualTo("101.0000");
            assertThat(value(s, "SELECT latest_price FROM stocks WHERE symbol='AAPL' AND market='US'")).isEqualTo("42.0000");
            assertThat(value(s, "SELECT name FROM stocks WHERE symbol='AAPL' AND market='US'")).isEqualTo("Existing Apple");
            assertThat(value(s, "SELECT COUNT(*) FROM stocks WHERE market='US' AND currency='USD'")).isEqualTo("15");
            assertThat(value(s, "SELECT status FROM alert_rules WHERE id=1")).isEqualTo("COOLDOWN");
            assertThat(value(s, "SELECT COUNT(*) FROM alert_triggers")).isEqualTo("1");
            assertThat(value(s, "SELECT status FROM notifications WHERE id=1")).isEqualTo("UNREAD");
            assertThat(value(s, "SELECT COUNT(*) FROM outbox_events WHERE status='QUARANTINED'")).isEqualTo("3");
            assertThat(value(s, "SELECT COUNT(*) FROM outbox_events WHERE status='SENT'")).isEqualTo("1");
            assertThat(value(s, "SELECT SUM(attempts) FROM outbox_events")).isEqualTo("8");
            assertThat(value(s, "SELECT COUNT(*) FROM simulation_runs")).isEqualTo("0");
            assertThat(value(s, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name IN
                    ('simulation_runs','simulation_rule_snapshots','simulation_steps','simulation_rule_events')
                    """)).isEqualTo("4");
        }
    }

    private static String value(Statement statement, String query) throws SQLException {
        try (ResultSet result = statement.executeQuery(query)) { result.next(); return result.getString(1); }
    }
}
