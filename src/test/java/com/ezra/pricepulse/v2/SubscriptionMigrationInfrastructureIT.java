package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SubscriptionMigrationInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("subscription_migration");
    @Test void additiveMigrationPreservesExistingFactsSeedsOnceAndNeverFabricatesConfirmation()throws Exception {
        var config=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration");
        config.target("8").load().migrate();String before;
        try(var c=connection();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'one@fixture.test','test','One'),(2,'two@fixture.test','test','Two')");
            s.executeUpdate("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,status,version) VALUES(1,1,1,'Existing','ABOVE',100,'ONCE','COMPLETED',9)");
            s.executeUpdate("INSERT INTO quote_events(id,event_key,stock_id,price,event_time,source,origin_type,processing_status) VALUES(1,'kept',1,101,'2026-10-28 14:00:00','ALPACA_IEX','REAL','PROCESSED')");
            s.executeUpdate("INSERT INTO alert_triggers(id,trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot) VALUES(1,'kept',1,1,101,'2026-10-28 14:00:00','{\"name\":\"Kept\"}')");
            s.executeUpdate("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status) VALUES('ALERT_TRIGGER',1,'ALERT_TRIGGERED','{}','SENT')");
            s.executeUpdate("INSERT INTO notifications(user_id,trigger_id,title,content) VALUES(1,1,'Kept','Kept')");
            before=facts(s);
        }
        var latest=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").target("9").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        try(var c=connection();var s=c.createStatement()){
            assertThat(facts(s)).isEqualTo(before);
            assertThat(value(s,"SELECT COUNT(*) FROM user_stock_subscriptions")).isEqualTo("30");
            assertThat(value(s,"SELECT COUNT(*) FROM market_subscription_targets WHERE state='PENDING' AND legacy_hold AND slot_reserved AND confirmed_epoch IS NULL")).isEqualTo("15");
            assertThat(value(s,"SELECT symbol_limit FROM market_subscription_control WHERE id=1")).isEqualTo("15");
            s.executeUpdate("UPDATE user_stock_subscriptions SET desired=FALSE WHERE user_id=1 AND stock_id=1");
            s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(3,'new@fixture.test','test','New')");
            assertThat(value(s,"SELECT COUNT(*) FROM user_stock_subscriptions WHERE user_id=3")).isEqualTo("0");
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try(var c=connection();var s=c.createStatement()){
            assertThat(value(s,"SELECT desired FROM user_stock_subscriptions WHERE user_id=1 AND stock_id=1")).isEqualTo("0");
            assertThat(value(s,"SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='stocks' AND column_name='name'")).isEqualTo("512");
        }
        var coordinated=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load();
        assertThat(coordinated.migrate().migrationsExecuted).isEqualTo(1);
        try(var c=connection();var s=c.createStatement()){
            assertThat(facts(s)).isEqualTo(before);
            assertThat(value(s,"SELECT COUNT(*) FROM market_subscription_targets WHERE intent_version=0 AND legacy_hold AND slot_reserved AND state='PENDING'")).isEqualTo("15");
            assertThat(value(s,"SELECT desired FROM user_stock_subscriptions WHERE user_id=1 AND stock_id=1")).isEqualTo("0");
            assertThat(value(s,"SELECT COUNT(*) FROM user_stock_subscriptions WHERE user_id=3")).isEqualTo("0");
        }
        assertThat(coordinated.migrate().migrationsExecuted).isZero();
    }
    Connection connection()throws SQLException{return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());}
    String value(Statement s,String sql)throws SQLException{try(var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    String facts(Statement s)throws SQLException{return value(s,"SELECT CONCAT(r.status,'|',r.version,'|',r.effective_at,'|',r.updated_at,'|',q.price,'|',q.event_time,'|',t.rule_snapshot,'|',o.status,'|',n.content) FROM alert_rules r JOIN quote_events q ON q.id=1 JOIN alert_triggers t ON t.rule_id=r.id JOIN outbox_events o ON o.aggregate_id=t.id JOIN notifications n ON n.trigger_id=t.id WHERE r.id=1");}
}
