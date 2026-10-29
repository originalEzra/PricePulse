package com.ezra.pricepulse.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    int quarantineLegacy();
    @Insert("""
            INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
            SELECT 'ALERT_TRIGGER', #{triggerId}, 'ALERT_TRIGGERED', #{payload}
            FROM alert_triggers t JOIN quote_events q ON q.id=t.quote_event_id
            WHERE t.id=#{triggerId} AND q.origin_type='REAL'
            """)
    int insertAlertTriggered(@Param("triggerId") long triggerId, @Param("payload") String payload);

    List<OutboxEvent> selectClaimable(
            @Param("now") LocalDateTime now,
            @Param("leaseCutoff") LocalDateTime leaseCutoff,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);

    int claim(
            @Param("ids") List<Long> ids,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now);

    int markPublished(
            @Param("id") long id,
            @Param("workerId") String workerId,
            @Param("publishedAt") LocalDateTime publishedAt);

    int markFailed(
            @Param("id") long id,
            @Param("workerId") String workerId,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError);

    int failExpiredExhaustedLeases(
            @Param("leaseCutoff") LocalDateTime leaseCutoff,
            @Param("maxAttempts") int maxAttempts,
            @Param("now") LocalDateTime now);
}
