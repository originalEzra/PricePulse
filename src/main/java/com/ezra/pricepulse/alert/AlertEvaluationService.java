package com.ezra.pricepulse.alert;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.matching.MatchingResult;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.outbox.OutboxWriter;
import com.ezra.pricepulse.stock.Stock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class AlertEvaluationService {
    private final AlertRuleMapper rules;
    private final AlertTriggerMapper triggers;
    private final RuleIndex index;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public AlertEvaluationService(AlertRuleMapper rules, AlertTriggerMapper triggers,
            RuleIndex index, OutboxWriter outbox, ObjectMapper json) {
        this.rules = rules;
        this.triggers = triggers;
        this.index = index;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MatchingResult evaluate(Stock stock, long quoteId, BigDecimal price, LocalDateTime eventTime) {
        BigDecimal previous = stock.latestQuoteAt() == null ? null : stock.latestPrice();
        List<Long> ids = index.candidates(stock, previous, price);
        if (ids.isEmpty()) {
            return MatchingResult.empty();
        }
        int triggered = 0;
        int rearmed = 0;
        List<AlertRule> changed = new ArrayList<>();
        // Fetch only Redis candidates; MySQL remains the final authority for status and thresholds.
        for (int offset = 0; offset < ids.size(); offset += 500) {
            List<Long> page = ids.subList(offset, Math.min(offset + 500, ids.size()));
            for (AlertRule rule : rules.findCandidates(stock.id(), page)) {
                if (previous == null) {
                    continue;
                }
                if (rule.getStatus() == AlertRuleStatus.ACTIVE && crosses(rule, previous, price)) {
                    AlertRuleStatus target = rule.getTriggerMode() == AlertTriggerMode.ONCE
                            ? AlertRuleStatus.COMPLETED : AlertRuleStatus.COOLDOWN;
                    requireTransition(rule, target, eventTime);
                    recordTrigger(rule, quoteId, price, eventTime);
                    rule.setStatus(target);
                    rule.setLastTriggeredAt(eventTime);
                    rule.setVersion(rule.getVersion() + 1);
                    changed.add(rule);
                    triggered++;
                } else if (rule.getStatus() == AlertRuleStatus.COOLDOWN && rearms(rule, price)) {
                    requireTransition(rule, AlertRuleStatus.ACTIVE, null);
                    rule.setStatus(AlertRuleStatus.ACTIVE);
                    rule.setVersion(rule.getVersion() + 1);
                    changed.add(rule);
                    rearmed++;
                }
            }
        }
        if (!changed.isEmpty()) {
            index.changed(stock, changed);
        }
        return new MatchingResult(ids.size(), triggered, rearmed);
    }

    @Transactional(readOnly = true)
    public List<AlertTriggerResponse> history(long userId, long ruleId, int limit) {
        return triggers.listOwned(userId, ruleId, limit).stream().map(trigger -> {
            try {
                return new AlertTriggerResponse(trigger.getId(), trigger.getRuleId(),
                        trigger.getQuoteEventId(), trigger.getTriggerPrice(), trigger.getEventTime(),
                        json.readTree(trigger.getRuleSnapshot()), trigger.getCreatedAt());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Cannot read rule snapshot", exception);
            }
        }).toList();
    }

    private boolean crosses(AlertRule rule, BigDecimal previous, BigDecimal current) {
        BigDecimal threshold = rule.getThresholdPrice();
        return rule.getDirection() == AlertDirection.ABOVE
                ? previous.compareTo(threshold) < 0 && current.compareTo(threshold) >= 0
                : previous.compareTo(threshold) > 0 && current.compareTo(threshold) <= 0;
    }

    private boolean rearms(AlertRule rule, BigDecimal current) {
        return rule.getDirection() == AlertDirection.ABOVE
                ? current.compareTo(rule.getRearmPrice()) <= 0
                : current.compareTo(rule.getRearmPrice()) >= 0;
    }

    private void requireTransition(AlertRule rule, AlertRuleStatus target, LocalDateTime triggeredAt) {
        if (rules.transitionByMatcher(rule.getId(), rule.getVersion(), rule.getStatus(), target, triggeredAt) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Rule changed during matching; retry quote");
        }
    }

    private void recordTrigger(AlertRule rule, long quoteId, BigDecimal price, LocalDateTime eventTime) {
        AlertTrigger trigger = new AlertTrigger();
        trigger.setTriggerKey("rule:" + rule.getId() + ":version:" + rule.getVersion());
        trigger.setRuleId(rule.getId());
        trigger.setQuoteEventId(quoteId);
        trigger.setTriggerPrice(price);
        trigger.setEventTime(eventTime);
        try {
            trigger.setRuleSnapshot(json.writeValueAsString(AlertRuleResponse.from(rule)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize rule snapshot", exception);
        }
        triggers.insert(trigger);
        outbox.alertTriggered(trigger.getId(), rule.getUserId(), rule.getId(), rule.getStockId());
    }
}
