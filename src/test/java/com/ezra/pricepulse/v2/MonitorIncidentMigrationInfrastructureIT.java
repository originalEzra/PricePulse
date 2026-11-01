package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class MonitorIncidentMigrationInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("monitor_migration_it");
    @Test void v10FactsSurviveAndNotificationsRequireExactlyOnePersistedSource()throws Exception{
        var before=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").target("10").load();before.migrate();
        String facts;
        try(var c=connection();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'synthetic@example.invalid','test','Test')");
            s.executeUpdate("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,status,version) VALUES(1,1,1,'Kept','ABOVE',205,'ONCE','COMPLETED',1)");
            s.executeUpdate("INSERT INTO quote_events(id,event_key,stock_id,price,event_time,source,origin_type,processing_status) VALUES(1,'kept',1,210,'2026-10-30 15:00:00','ALPACA_IEX','REAL','PROCESSED')");
            s.executeUpdate("INSERT INTO alert_triggers(id,trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot) VALUES(1,'kept',1,1,210,'2026-10-30 15:00:00','{\"name\":\"Kept\"}')");
            s.executeUpdate("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status) VALUES('ALERT_TRIGGER',1,'ALERT_TRIGGERED','{}','SENT')");
            s.executeUpdate("INSERT INTO notifications(id,user_id,trigger_id,title,content) VALUES(1,1,1,'Kept','Kept')");
            s.executeUpdate("INSERT INTO simulation_runs(id,user_id,stock_id,origin_snapshot,history_prefix_snapshot,parameters,seed,generator_version,calendar_snapshot,sim_clock,sim_clock_ns,latest_price,rule_index_token,start_request_key,start_request_hash) VALUES('00000000-0000-4000-8000-000000000001',1,1,'{}','[]','{}',7,'fixture','[]','2026-10-30 15:00:00',1,210,UUID(),'fixture',REPEAT('0',64))");
            facts=facts(s);
        }
        var latest=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);assertThat(latest.validateWithResult().validationSuccessful).isTrue();assertThat(latest.migrate().migrationsExecuted).isZero();
        try(var c=connection();var s=c.createStatement()){
            assertThat(facts(s)).isEqualTo(facts);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO notifications(user_id,title,content) VALUES(1,'bad','No source')")).isInstanceOf(SQLException.class);
            s.executeUpdate("INSERT INTO market_monitor_incidents(incident_key,active_scope,scope,reason,started_at) VALUES('00000000-0000-4000-8000-000000000002','CONNECTION','CONNECTION','HEARTBEAT_TIMEOUT','2026-11-02 15:00:00')");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO market_monitor_incidents(incident_key,active_scope,scope,reason,started_at) VALUES('00000000-0000-4000-8000-000000000003','CONNECTION','CONNECTION','CONNECT_FAILED','2026-11-02 15:00:01')")).isInstanceOf(SQLException.class);
            s.executeUpdate("INSERT INTO market_monitor_deliveries(id,incident_key,user_id,kind,event_time,symbols) VALUES(1,'00000000-0000-4000-8000-000000000002',1,'INTERRUPTED','2026-11-02 15:00:30','AAPL')");
            s.executeUpdate("INSERT INTO notifications(user_id,monitor_delivery_id,title,content) VALUES(1,1,'Monitor','No price trigger')");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO notifications(user_id,trigger_id,monitor_delivery_id,title,content) VALUES(1,1,1,'bad','Two sources')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO market_monitor_deliveries(incident_key,user_id,kind,event_time,symbols) VALUES('00000000-0000-4000-8000-000000000002',1,'INTERRUPTED','2026-11-02 15:00:30','AAPL')")).isInstanceOf(SQLException.class);
            assertThat(value(s,"SELECT COUNT(*) FROM alert_triggers")).isEqualTo("1");
        }
    }
    Connection connection()throws SQLException{return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());}
    String value(Statement s,String sql)throws SQLException{try(var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    String facts(Statement s)throws SQLException{return value(s,"SELECT CONCAT(r.status,'|',r.version,'|',r.effective_at,'|',q.price,'|',q.event_time,'|',t.rule_snapshot,'|',o.status,'|',n.content,'|',n.created_at,'|',sim.parameters,'|',sim.rule_index_token,'|',sim.latest_price) FROM alert_rules r JOIN quote_events q ON q.id=1 JOIN alert_triggers t ON t.id=1 JOIN outbox_events o ON o.id=1 JOIN notifications n ON n.id=1 JOIN simulation_runs sim ON sim.user_id=r.user_id WHERE r.id=1");}
}
