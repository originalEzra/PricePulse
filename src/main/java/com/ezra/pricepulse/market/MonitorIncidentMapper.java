package com.ezra.pricepulse.market;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.ezra.pricepulse.monitoring.MonitoringModels.Demand;

@Mapper
public interface MonitorIncidentMapper {
    record Incident(String incidentKey,String scope,Long stockId,String reason,LocalDateTime startedAt,
            LocalDateTime recoveredAt,long discardedTotal,LocalDateTime alertedAt) {
        MonitorFault restore(){return new MonitorFault(incidentKey,scope,stockId,reason,MarketTime.instant(startedAt),null,discardedTotal,1,null);}
    }
    record Audience(long userId,String symbols){}
    record Delivery(long id,String incidentKey,long userId,String kind,LocalDateTime eventTime,String symbols,
            String reason,LocalDateTime startedAt,LocalDateTime recoveredAt,long discardedTotal){}
    @Select("SELECT incident_key,scope,stock_id,reason,started_at,recovered_at,discarded_total,alerted_at FROM market_monitor_incidents WHERE recovered_at IS NULL ORDER BY started_at,incident_key")
    List<Incident> open();
    @Insert("""
        INSERT INTO market_monitor_incidents(incident_key,active_scope,scope,stock_id,reason,started_at,discarded_total)
        VALUES(#{f.id},#{f.activeScope},#{f.scope},#{f.stockId},#{f.reason},#{startedAt},#{f.discarded})
        ON DUPLICATE KEY UPDATE incident_key=market_monitor_incidents.incident_key
        """) int insert(@Param("f")MonitorFault fault,@Param("startedAt")LocalDateTime startedAt);
    @Select("SELECT incident_key,scope,stock_id,reason,started_at,recovered_at,discarded_total,alerted_at FROM market_monitor_incidents WHERE incident_key=#{id}")
    Incident find(String id);
    @Update("UPDATE market_monitor_incidents SET discarded_total=GREATEST(discarded_total,#{discarded}),recovered_at=COALESCE(recovered_at,#{recovered}),active_scope=CASE WHEN #{recovered} IS NULL THEN active_scope ELSE NULL END WHERE incident_key=#{id}")
    void update(@Param("id")String id,@Param("discarded")long discarded,@Param("recovered")LocalDateTime recovered);
    @Update("UPDATE market_monitor_incidents SET alerted_at=COALESCE(alerted_at,#{at}) WHERE incident_key=#{id}")
    void alerted(@Param("id")String id,@Param("at")LocalDateTime at);
    @Select("""
        <script>SELECT u.user_id,LEFT(GROUP_CONCAT(DISTINCT s.symbol ORDER BY s.symbol SEPARATOR ', '),480) symbols
        FROM user_stock_subscriptions u JOIN stocks s ON s.id=u.stock_id AND s.status='ACTIVE'
        JOIN users a ON a.id=u.user_id AND a.status='ACTIVE'
        WHERE u.desired=TRUE <if test="stock != null">AND u.stock_id=#{stock}</if> GROUP BY u.user_id ORDER BY u.user_id</script>
        """) List<Audience> audience(@Param("stock")Long stock);
    @Select("SELECT s.id stock_id,s.symbol,COALESCE(t.intent_version,0) intent_version FROM stocks s LEFT JOIN market_subscription_targets t ON t.stock_id=s.id WHERE s.status='ACTIVE' AND (t.legacy_hold=TRUE OR EXISTS(SELECT 1 FROM user_stock_subscriptions u WHERE u.stock_id=s.id AND u.desired=TRUE)) ORDER BY s.id")
    List<Demand> expected();
    @Select("SELECT user_id,symbols FROM market_monitor_deliveries WHERE incident_key=#{id} AND kind='INTERRUPTED' ORDER BY user_id")
    List<Audience> interruptedAudience(String id);
    @Insert("INSERT INTO market_monitor_deliveries(incident_key,user_id,kind,event_time,symbols) VALUES(#{id},#{user},#{kind},#{at},#{symbols})")
    void insertDelivery(@Param("id")String id,@Param("user")long user,@Param("kind")String kind,@Param("at")LocalDateTime at,@Param("symbols")String symbols);
    @Select("SELECT id FROM market_monitor_deliveries WHERE incident_key=#{id} AND user_id=#{user} AND kind=#{kind}")
    Long deliveryId(@Param("id")String id,@Param("user")long user,@Param("kind")String kind);
    @Select("SELECT d.id,d.incident_key,d.user_id,d.kind,d.event_time,d.symbols,i.reason,i.started_at,i.recovered_at,i.discarded_total FROM market_monitor_deliveries d JOIN market_monitor_incidents i ON i.incident_key=d.incident_key WHERE d.id=#{id}")
    Delivery delivery(long id);
}
