package com.ezra.pricepulse.alert;

import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AlertRuleMapper {
    @org.apache.ibatis.annotations.Select("SELECT id FROM alert_rules WHERE stock_id=#{stockId} AND status='COOLDOWN' ORDER BY id")
    List<Long> cooldownIds(long stockId);
    List<AlertRule> listSnapshotSource(@Param("userId") long userId, @Param("stockId") long stockId);
    int insert(@Param("rule") AlertRule rule);

    AlertRule findOwned(@Param("ruleId") long ruleId, @Param("userId") long userId);

    List<AlertRule> listOwned(
            @Param("userId") long userId,
            @Param("stockId") Long stockId,
            @Param("status") AlertRuleStatus status,
            @Param("limit") int limit);

    int updateDetails(
            @Param("rule") AlertRule rule,
            @Param("expectedVersion") long expectedVersion,
            @Param("allowedStatuses") Collection<AlertRuleStatus> allowedStatuses);

    int updateStatus(
            @Param("ruleId") long ruleId,
            @Param("userId") long userId,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetStatus") AlertRuleStatus targetStatus,
            @Param("allowedStatuses") Collection<AlertRuleStatus> allowedStatuses);

    int softDelete(
            @Param("ruleId") long ruleId,
            @Param("userId") long userId,
            @Param("expectedVersion") long expectedVersion);

    List<AlertRule> listIndexable(@Param("stockId") long stockId);

    List<AlertRule> findCandidates(
            @Param("stockId") long stockId, @Param("ids") Collection<Long> ids);

    int transitionByMatcher(
            @Param("ruleId") long ruleId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatus") AlertRuleStatus expectedStatus,
            @Param("targetStatus") AlertRuleStatus targetStatus,
            @Param("triggeredAt") LocalDateTime triggeredAt);
}
