package com.ezra.pricepulse.outbox;

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
}
