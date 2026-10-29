package com.ezra.pricepulse.market;

import com.ezra.pricepulse.market.MarketDataModels.*;
import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MarketDataMapper {
    @Select("SELECT * FROM market_stream_state WHERE stock_id=#{stockId}")
    StreamState streamState(long stockId);
    @Insert("""
        INSERT INTO market_stream_state(stock_id,provider,feed,event_time_ns,accepted_sequence,continuity_id,received_at,processed_at)
        VALUES(#{stockId},#{provider},#{feed},#{eventTimeNs},#{acceptedSequence},#{continuityId},#{receivedAt},#{processedAt}) AS v
        ON DUPLICATE KEY UPDATE provider=v.provider,feed=v.feed,event_time_ns=v.event_time_ns,
        accepted_sequence=v.accepted_sequence,continuity_id=v.continuity_id,received_at=v.received_at,processed_at=v.processed_at
        """)
    int saveStreamState(StreamState state);
    @Select("SELECT session_date,opens_at,closes_at FROM market_sessions WHERE provider='ALPACA' ORDER BY session_date")
    List<Session> sessions();
    @Insert("""
        INSERT INTO market_sessions(provider,session_date,opens_at,closes_at,fetched_at)
        VALUES('ALPACA',#{s.sessionDate},#{s.opensAt},#{s.closesAt},#{now}) AS v
        ON DUPLICATE KEY UPDATE opens_at=v.opens_at,closes_at=v.closes_at,fetched_at=v.fetched_at
        """)
    void saveSession(@Param("s") Session session, @Param("now") LocalDateTime now);
    void saveBars(@Param("bars") List<Bar> bars);
    @Select("""
        SELECT id,stock_id,interval_start,interval_end,open_price,high_price,low_price,close_price,revision,fetched_at
        FROM market_price_bars WHERE stock_id=#{stockId} AND provider='ALPACA' AND feed='iex'
          AND resolution='1Min' AND adjustment='raw' AND interval_end>=#{from} AND interval_end<=#{to}
        ORDER BY interval_end,id
        """)
    List<Bar> bars(@Param("stockId") long stockId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    @Insert("""
        INSERT INTO market_history_coverage(stock_id,provider,feed,requested_from,requested_to,synced_at)
        VALUES(#{stockId},'ALPACA','iex',#{from},#{to},#{now}) AS v
        ON DUPLICATE KEY UPDATE requested_from=v.requested_from,requested_to=v.requested_to,synced_at=v.synced_at
        """)
    void saveCoverage(@Param("stockId") long stockId, @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to, @Param("now") LocalDateTime now);
    @Select("SELECT requested_from,requested_to,synced_at FROM market_history_coverage WHERE stock_id=#{stockId} AND provider='ALPACA' AND feed='iex'")
    Coverage coverage(long stockId);
    @Select("""
        SELECT COALESCE(MAX(t.id),0) FROM alert_triggers t
        JOIN alert_rules r ON r.id=t.rule_id JOIN quote_events q ON q.id=t.quote_event_id
        WHERE r.user_id=#{userId} AND q.stock_id=#{stockId} AND q.origin_type='REAL'
          AND q.provider='ALPACA' AND q.feed='iex'
        """)
    long eventWatermark(@Param("userId") long userId, @Param("stockId") long stockId);
    @Select("""
        SELECT t.id AS trigger_id,q.id AS quote_id,q.event_time_ns,t.trigger_price AS price,t.rule_snapshot,
          CASE WHEN n.id IS NOT NULL THEN n.status
               WHEN o.status IN ('FAILED','QUARANTINED') THEN 'FAILED' ELSE 'PENDING' END AS notification_status
        FROM alert_triggers t JOIN alert_rules r ON r.id=t.rule_id
        JOIN quote_events q ON q.id=t.quote_event_id
        LEFT JOIN notifications n ON n.trigger_id=t.id AND n.user_id=#{userId}
        LEFT JOIN outbox_events o ON o.aggregate_type='ALERT_TRIGGER' AND o.aggregate_id=t.id AND o.event_type='ALERT_TRIGGERED'
        WHERE r.user_id=#{userId} AND q.stock_id=#{stockId} AND q.origin_type='REAL'
          AND q.provider='ALPACA' AND q.feed='iex'
          AND q.event_time_ns>=#{fromNs} AND q.event_time_ns<=#{toNs}
          AND t.id>#{afterId} AND t.id<=#{watermark}
        ORDER BY t.id LIMIT 101
        """)
    List<EventRow> events(@Param("userId") long userId,@Param("stockId") long stockId,
            @Param("fromNs") long fromNs,@Param("toNs") long toNs,@Param("afterId") long afterId,@Param("watermark") long watermark);
    @Select("""
        SELECT event_time_ns FROM quote_events WHERE stock_id=#{stockId} AND origin_type='REAL'
          AND provider='ALPACA' AND feed='iex' AND processing_status='PROCESSED'
          AND baseline_reason IS NOT NULL AND event_time_ns BETWEEN #{fromNs} AND #{toNs}
        ORDER BY event_time_ns
        """)
    List<Long> baselineTimes(@Param("stockId") long stockId,@Param("fromNs") long fromNs,@Param("toNs") long toNs);
    @Select("""
        SELECT id,event_time_ns,price FROM (
          SELECT id,event_time_ns,price,
             ROW_NUMBER() OVER(PARTITION BY FLOOR((event_time_ns-#{fromNs})/#{bucketNs}) ORDER BY event_time_ns DESC,id DESC) AS rn
          FROM quote_events WHERE stock_id=#{stockId} AND origin_type='REAL' AND provider='ALPACA'
            AND feed='iex' AND processing_status='PROCESSED' AND event_time_ns BETWEEN #{fromNs} AND #{toNs}
        ) sampled WHERE rn=1 ORDER BY event_time_ns,id
        """)
    List<TradePoint> tradePoints(@Param("stockId") long stockId,@Param("fromNs") long fromNs,
            @Param("toNs") long toNs,@Param("bucketNs") long bucketNs);
    record TradePoint(long id,long eventTimeNs,java.math.BigDecimal price) {}

    @Select("""
        SELECT id,stock_id,interval_start,interval_end,open_price,high_price,low_price,close_price,revision,fetched_at
        FROM market_price_bars WHERE id=#{id} AND stock_id=#{stockId} AND provider='ALPACA' AND feed='iex'
          AND resolution='1Min' AND adjustment='raw'
        """)
    Bar simulationBar(@Param("stockId") long stockId,@Param("id") long id);
    @Select("""
        SELECT id,event_time_ns,price FROM quote_events WHERE id=#{id} AND stock_id=#{stockId}
          AND origin_type='REAL' AND provider='ALPACA' AND feed='iex' AND processing_status='PROCESSED'
          AND event_time_ns IS NOT NULL AND accepted_sequence IS NOT NULL
        """)
    TradePoint simulationTrade(@Param("stockId") long stockId,@Param("id") long id);
    @Select("""
        SELECT q.id,q.event_time_ns,q.price FROM quote_events q JOIN market_stream_state s ON s.stock_id=q.stock_id
          AND s.event_time_ns=q.event_time_ns AND s.accepted_sequence=q.accepted_sequence
        WHERE q.stock_id=#{stockId} AND q.origin_type='REAL' AND q.provider='ALPACA' AND q.feed='iex'
          AND s.provider='ALPACA' AND s.feed='iex' AND q.processing_status='PROCESSED'
        ORDER BY q.id DESC LIMIT 1
        """)
    TradePoint latestSimulationTrade(long stockId);
}
