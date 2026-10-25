package com.ezra.pricepulse.alert;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertTriggerMapper {
    @Insert("""
            INSERT INTO alert_triggers
                (trigger_key, rule_id, quote_event_id, trigger_price, event_time, rule_snapshot)
            VALUES (#{triggerKey}, #{ruleId}, #{quoteEventId}, #{triggerPrice}, #{eventTime}, #{ruleSnapshot})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AlertTrigger trigger);

    @Select("""
            SELECT t.id, t.trigger_key, t.rule_id, t.quote_event_id, t.trigger_price,
                   t.event_time, t.rule_snapshot, t.created_at
            FROM alert_triggers t JOIN alert_rules r ON r.id = t.rule_id
            WHERE r.user_id = #{userId} AND r.id = #{ruleId}
            ORDER BY t.id DESC LIMIT #{limit}
            """)
    List<AlertTrigger> listOwned(@Param("userId") long userId,
            @Param("ruleId") long ruleId, @Param("limit") int limit);
}
