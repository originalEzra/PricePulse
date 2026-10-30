package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class V2CleanMigrationInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7").withDatabaseName("v2_clean");

    @Test void emptyDatabaseMigratesThroughV8AndRepeatedStartupDoesNotChangeFacts() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); var s = c.createStatement()) {
            assertThat(value(s, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(10);
            assertThat(value(s, "SELECT COUNT(*) FROM stocks WHERE market='US' AND symbol IN ('AAPL','MSFT','NVDA','AMZN','GOOGL')")).isEqualTo(5);
            assertThat(value(s, "SELECT COUNT(*) FROM stocks WHERE market='US' AND currency='USD' AND status='ACTIVE'")).isEqualTo(15);
            assertThat(value(s, "SELECT COUNT(*) FROM stocks WHERE latest_price IS NOT NULL")).isZero();
            for (String table : new String[]{"quote_events","alert_triggers","outbox_events","notifications","market_stream_state",
                    "simulation_runs","simulation_rule_snapshots","simulation_steps","simulation_rule_events"})
                assertThat(value(s, "SELECT COUNT(*) FROM " + table)).as(table).isZero();
        }
    }
    private long value(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
}
