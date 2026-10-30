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
                (trigger_key, rule_id, quote_event_id, trigger_price, event_time, rule_snapshot, trigger_context)
            VALUES (#{triggerKey}, #{ruleId}, #{quoteEventId}, #{triggerPrice}, #{eventTime}, #{ruleSnapshot}, #{triggerContext})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AlertTrigger trigger);

    @Select("""
            SELECT t.id, t.trigger_key, t.rule_id, t.quote_event_id, t.trigger_price,
                   t.event_time, t.rule_snapshot, t.trigger_context, t.created_at
            FROM alert_triggers t JOIN alert_rules r ON r.id = t.rule_id
            JOIN quote_events q ON q.id = t.quote_event_id AND q.origin_type = 'REAL'
            WHERE r.user_id = #{userId} AND r.id = #{ruleId}
            ORDER BY t.id DESC LIMIT #{limit}
            """)
    List<AlertTrigger> listOwned(@Param("userId") long userId,
            @Param("ruleId") long ruleId, @Param("limit") int limit);

    @Select("SELECT id FROM alert_triggers WHERE rule_id = #{ruleId} AND quote_event_id = #{quoteId}")
    Long findIdForQuote(@Param("ruleId") long ruleId, @Param("quoteId") long quoteId);
}
