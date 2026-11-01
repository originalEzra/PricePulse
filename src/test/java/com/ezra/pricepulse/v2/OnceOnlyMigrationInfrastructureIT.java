package com.ezra.pricepulse.v2;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class OnceOnlyMigrationInfrastructureIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("once_migration_it");
    Flyway flyway(String target){var c=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).locations("classpath:db/migration").cleanDisabled(false);if(target!=null)c.target(target);return c.load();}
    @BeforeEach void freshDisposableSchema(){flyway(null).clean();}
    Connection connection()throws SQLException{return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());}
    String value(Statement s,String sql)throws SQLException{try(var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    @Test void v10OnceRowsAllHistoryAndSavedSimulationSurviveV11AndV12()throws Exception{
        flyway("10").migrate();Map<String,List<String>> before;
        try(var c=connection();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'fixture@example.invalid','test','SYNTHETIC')");
            for(int id=1;id<=37;id++)s.executeUpdate("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,status,version) VALUES("+id+",1,1,'Kept','ABOVE',205,'ONCE','"+(id<=16?"ACTIVE":"COMPLETED")+"',7)");
            for(int id=1;id<=21;id++){
                s.executeUpdate("INSERT INTO quote_events(id,event_key,stock_id,price,event_time,source,origin_type,processing_status) VALUES("+id+",'q"+id+"',1,210,'2026-10-30 15:00:00','ALPACA_IEX','REAL','PROCESSED')");
                s.executeUpdate("INSERT INTO alert_triggers(id,trigger_key,rule_id,quote_event_id,trigger_price,event_time,rule_snapshot) VALUES("+id+",'t"+id+"',"+(id+16)+","+id+",210,'2026-10-30 15:00:00','{\"name\":\"Kept\",\"triggerMode\":\"ONCE\",\"rearmPrice\":null}')");
                s.executeUpdate("INSERT INTO outbox_events(aggregate_type,aggregate_id,event_type,payload,status) VALUES('ALERT_TRIGGER',"+id+",'ALERT_TRIGGERED','{}','SENT')");
                s.executeUpdate("INSERT INTO notifications(user_id,trigger_id,title,content) VALUES(1,"+id+",'Kept','Kept')");
            }
            s.executeUpdate("INSERT INTO simulation_runs(id,user_id,stock_id,origin_snapshot,history_prefix_snapshot,parameters,seed,generator_version,calendar_snapshot,sim_clock,sim_clock_ns,latest_price,rule_index_token,start_request_key,start_request_hash) VALUES('00000000-0000-4000-8000-000000000001',1,1,'{}','[]','{}',7,'splitmix64-price-v1','[]','2026-10-30 15:00:00',1,210,UUID(),'fixture',REPEAT('0',64))");
            s.executeUpdate("INSERT INTO simulation_rule_snapshots(run_id,source_rule_id,source_version,config_json,source_status,initial_state,current_state) VALUES('00000000-0000-4000-8000-000000000001',17,1,'{\"triggerMode\":\"ONCE\"}','COMPLETED','ACTIVE','COMPLETED')");
            before=facts(s);
        }
        var latest=flyway(null);assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);assertThat(latest.migrate().migrationsExecuted).isZero();assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        try(var c=connection();var s=c.createStatement()){
            assertThat(facts(s)).isEqualTo(before);
            assertThat(value(s,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_rules' AND column_name IN ('trigger_mode','rearm_price')")).isEqualTo("0");
            assertThatThrownBy(()->s.executeUpdate("UPDATE alert_rules SET status='COOLDOWN' WHERE id=1")).isInstanceOf(SQLException.class);
        }
    }
    @ParameterizedTest @CsvSource({"REPEAT,ACTIVE,200","REPEAT,COOLDOWN,200","REPEAT,DELETED,200","ONCE,PAUSED,NULL"})
    void incompatibleRowsBlockBeforeAnyColumnOrRuleIsChanged(String mode,String state,String rearm)throws Exception{
        flyway("11").migrate();String before;
        try(var c=connection();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO users(id,email,password_hash,display_name) VALUES(1,'legacy@example.invalid','test','SYNTHETIC')");
            s.executeUpdate("INSERT INTO alert_rules(id,user_id,stock_id,name,direction,threshold_price,trigger_mode,rearm_price,status,version) VALUES(1,1,1,'Legacy','ABOVE',205,'"+mode+"',"+rearm+",'"+state+"',9)");
            before=value(s,"SELECT CONCAT(trigger_mode,'|',IFNULL(rearm_price,'NULL'),'|',status,'|',version,'|',created_at,'|',updated_at) FROM alert_rules WHERE id=1");
        }
        assertThatThrownBy(()->flyway(null).migrate()).isInstanceOf(RuntimeException.class);
        try(var c=connection();var s=c.createStatement()){
            assertThat(value(s,"SELECT CONCAT(trigger_mode,'|',IFNULL(rearm_price,'NULL'),'|',status,'|',version,'|',created_at,'|',updated_at) FROM alert_rules WHERE id=1")).isEqualTo(before);
            assertThat(value(s,"SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='alert_rules' AND constraint_name LIKE 'chk_alert_rules_once%' ")).isEqualTo("0");
        }
    }
    Map<String,List<String>> facts(Statement s)throws SQLException{
        var result=new LinkedHashMap<String,List<String>>();
        for(String table:List.of("alert_rules","quote_events","alert_triggers","outbox_events","notifications","simulation_runs","simulation_rule_snapshots")){
            String columns=table.equals("alert_rules")?"id,user_id,stock_id,name,note,direction,threshold_price,status,last_triggered_at,version,created_at,updated_at,effective_at":table.equals("notifications")?"id,user_id,trigger_id,title,content,status,read_at,created_at,updated_at":"*";
            var rows=new ArrayList<String>();try(var r=s.executeQuery("SELECT "+columns+" FROM "+table+" ORDER BY id")){int count=r.getMetaData().getColumnCount();while(r.next()){var values=new ArrayList<String>();for(int i=1;i<=count;i++)values.add(r.getString(i));rows.add(values.toString());}}result.put(table,rows);
        }return result;
    }
}
