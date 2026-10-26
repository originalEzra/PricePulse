package com.ezra.pricepulse.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {
    @Insert("""
            INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
            VALUES ('ALERT_TRIGGER', #{triggerId}, 'ALERT_TRIGGERED', #{payload})
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
