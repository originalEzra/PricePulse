package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.simulation.SimulationModels.*;
import com.ezra.pricepulse.alert.AlertRuleStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SimulationMapper {
    String RUN = "id,user_id,stock_id,origin_snapshot,history_prefix_snapshot,parameters,seed,"
            + "generator_version,calendar_snapshot,sim_clock,sim_clock_ns,latest_price,next_point_seq,"
            + "last_batch_no,version,rule_index_token,start_request_key,start_request_hash";
    String SNAP = "id,run_id,source_rule_id,source_version,config_json,source_status,initial_state,"
            + "current_state,state_version,last_triggered_at";
    String STEP = "id,run_id,batch_no,request_key,request_hash,from_clock,to_clock,from_clock_ns,to_clock_ns,"
            + "first_point_seq,last_point_seq,points_json,summary_json";

    @Select("SELECT " + RUN + " FROM simulation_runs WHERE user_id=#{userId}")
    Run current(long userId);

    @Select("SELECT " + RUN + " FROM simulation_runs WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Run lockOwned(@Param("id") String id, @Param("userId") long userId);

    @Insert("""
            INSERT INTO simulation_runs (id,user_id,stock_id,origin_snapshot,history_prefix_snapshot,parameters,
                seed,generator_version,calendar_snapshot,sim_clock,sim_clock_ns,latest_price,
                rule_index_token,start_request_key,start_request_hash)
            VALUES (#{r.id},#{r.userId},#{r.stockId},#{r.originSnapshot},#{r.historyPrefixSnapshot},#{r.parameters},
                #{r.seed},#{r.generatorVersion},#{r.calendarSnapshot},#{r.simClock},#{r.simClockNs},#{r.latestPrice},
                #{r.ruleIndexToken},#{r.startRequestKey},#{r.startRequestHash})
            """)
    int insertRun(@Param("r") Run run);

    @Delete("DELETE FROM simulation_runs WHERE id=#{id} AND user_id=#{userId}")
    int deleteOwned(@Param("id") String id, @Param("userId") long userId);

    @Insert("""
            INSERT INTO simulation_rule_snapshots (run_id,source_rule_id,source_version,config_json,
                source_status,initial_state,current_state)
            VALUES (#{s.runId},#{s.sourceRuleId},#{s.sourceVersion},#{s.configJson},
                #{s.sourceStatus},#{s.initialState},#{s.currentState})
            """)
    int insertSnapshot(@Param("s") Snapshot snapshot);

    @Select("SELECT " + SNAP + " FROM simulation_rule_snapshots WHERE run_id=#{runId} ORDER BY id")
    List<Snapshot> snapshots(String runId);

    @Select("SELECT " + SNAP + " FROM simulation_rule_snapshots WHERE run_id=#{runId}"
            + " AND current_state IN ('ACTIVE','COOLDOWN') ORDER BY id")
    List<Snapshot> indexable(String runId);

    @Select("""
            <script>
            SELECT
            """ + SNAP + " " + """
            FROM simulation_rule_snapshots WHERE run_id=#{runId}
              AND current_state IN ('ACTIVE','COOLDOWN') AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id
            </script>
            """)
    List<Snapshot> candidates(@Param("runId") String runId, @Param("ids") List<Long> ids);

    @Update("""
            UPDATE simulation_rule_snapshots SET current_state=#{target},state_version=state_version+1,
                last_triggered_at=COALESCE(#{triggeredAt},last_triggered_at)
            WHERE run_id=#{s.runId} AND id=#{s.id} AND current_state=#{s.currentState} AND state_version=#{s.stateVersion}
            """)
    int transition(@Param("s") Snapshot snapshot, @Param("target") AlertRuleStatus target,
            @Param("triggeredAt") LocalDateTime triggeredAt);

    @Update("UPDATE simulation_runs SET rule_index_token=#{token} WHERE id=#{id}")
    int indexToken(@Param("id") String id, @Param("token") String token);

    @Insert("""
            INSERT INTO simulation_steps (run_id,batch_no,request_key,request_hash,from_clock,to_clock,
                from_clock_ns,to_clock_ns,first_point_seq,last_point_seq,points_json,summary_json)
            VALUES (#{s.runId},#{s.batchNo},#{s.requestKey},#{s.requestHash},#{s.fromClock},#{s.toClock},
                #{s.fromClockNs},#{s.toClockNs},#{s.firstPointSeq},#{s.lastPointSeq},#{s.pointsJson},#{s.summaryJson})
            """)
    int insertStep(@Param("s") Step step);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("SELECT " + STEP + " FROM simulation_steps WHERE run_id=#{runId} AND request_key=#{key}")
    Step byRequest(@Param("runId") String runId, @Param("key") String key);

    @Update("UPDATE simulation_steps SET summary_json=#{summary} WHERE id=#{id}")
    int summary(@Param("id") long id, @Param("summary") String summary);

    @Insert("""
            INSERT INTO simulation_rule_events (run_id,step_id,snapshot_rule_id,point_seq,event_type,
                event_time,event_time_ns,price,from_state,to_state)
            VALUES (#{e.runId},#{e.stepId},#{e.snapshotRuleId},#{e.pointSeq},#{e.eventType},
                #{e.eventTime},#{e.eventTimeNs},#{e.price},#{e.fromState},#{e.toState})
            """)
    int insertEvent(@Param("e") Event event);

    @Update("""
            UPDATE simulation_runs SET sim_clock=#{s.toClock},sim_clock_ns=#{s.toClockNs},latest_price=#{price},
                next_point_seq=#{s.lastPointSeq}+1,last_batch_no=#{s.batchNo},version=version+1
            WHERE id=#{s.runId} AND version=#{expectedVersion}
            """)
    int finish(@Param("s") Step step, @Param("price") java.math.BigDecimal price,
            @Param("expectedVersion") long version);

    @Select("""
            SELECT id,run_id,step_id,snapshot_rule_id,point_seq,event_type,event_time,event_time_ns,price,from_state,to_state
            FROM simulation_rule_events WHERE run_id=#{runId} ORDER BY id
            """)
    List<Event> events(String runId);

    @Select("SELECT " + STEP + " FROM simulation_steps WHERE run_id=#{runId} AND batch_no=#{batch}")
    Step step(@Param("runId") String runId,@Param("batch") long batch);

    @Select("SELECT " + STEP + " FROM simulation_steps WHERE run_id=#{runId} AND batch_no>#{after} AND batch_no<=#{watermark}"
            + " AND to_clock_ns>=#{from} AND from_clock_ns<=#{to} ORDER BY batch_no LIMIT 11")
    List<Step> pageSteps(@Param("runId") String runId,@Param("after") long after,@Param("watermark") long watermark,
            @Param("from") long from,@Param("to") long to);

    @Select("SELECT COALESCE(MAX(id),0) FROM simulation_rule_events WHERE run_id=#{runId}")
    long eventWatermark(String runId);

    @Select("""
        SELECT id,run_id,step_id,snapshot_rule_id,point_seq,event_type,event_time,event_time_ns,price,from_state,to_state
        FROM simulation_rule_events WHERE run_id=#{runId} AND id>#{after} AND id<=#{watermark}
          AND event_time_ns>=#{from} AND event_time_ns<=#{to} ORDER BY id LIMIT 101
        """)
    List<Event> pageEvents(@Param("runId") String runId,@Param("after") long after,@Param("watermark") long watermark,
            @Param("from") long from,@Param("to") long to);

    @Select("SELECT event_type,COUNT(*) AS total FROM simulation_rule_events WHERE run_id=#{runId} GROUP BY event_type")
    List<EventCount> eventCounts(String runId);
    @Select("""
        SELECT e.event_type,COUNT(*) AS total FROM simulation_rule_events e
        JOIN simulation_steps s ON s.id=e.step_id AND s.run_id=e.run_id
        WHERE e.run_id=#{runId} AND s.batch_no<=#{watermark} GROUP BY e.event_type
        """)
    List<EventCount> eventCountsAt(@Param("runId") String runId,@Param("watermark") long watermark);
    record EventCount(String eventType,long total) {}
}
