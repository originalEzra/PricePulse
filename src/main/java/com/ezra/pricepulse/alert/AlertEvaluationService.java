package com.ezra.pricepulse.alert;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.matching.MatchingResult;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.market.QuoteTiming;
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
        return evaluate(stock, quoteId, price, eventTime, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MatchingResult evaluate(Stock stock, long quoteId, BigDecimal price, LocalDateTime eventTime, boolean baseline) {
        return evaluate(stock,quoteId,price,eventTime,baseline,null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MatchingResult evaluate(Stock stock, long quoteId, BigDecimal price, LocalDateTime eventTime,
            boolean baseline, OpeningGapContext opening) {
        return evaluate(stock,quoteId,price,eventTime,baseline,opening,
                new TargetReachContext("TARGET_REACHED",baseline?"RECOVERY":"LIVE",eventTime.toInstant(java.time.ZoneOffset.UTC),eventTime.toInstant(java.time.ZoneOffset.UTC),baseline?"STARTUP_OR_RECONNECT":null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MatchingResult evaluate(Stock stock, long quoteId, BigDecimal price, LocalDateTime eventTime,
            boolean baseline, OpeningGapContext opening, TargetReachContext confirmation) {
        List<Long> ids = index.candidates(stock,price);
        if (ids.isEmpty()) {
            return MatchingResult.empty();
        }
        int triggered = 0;
        List<AlertRule> changed = new ArrayList<>();
        // Fetch only Redis candidates; MySQL remains the final authority for status and thresholds.
        for (int offset = 0; offset < ids.size(); offset += 500) {
            List<Long> page = ids.subList(offset, Math.min(offset + 500, ids.size()));
            List<OutboxWriter.PendingAlert> pending = new ArrayList<>();
            for (AlertRule rule : rules.findCandidates(stock.id(), page)) {
                if(rule.getStatus()==AlertRuleStatus.ACTIVE
                        && rule.getEffectiveAt()!=null && confirmation.confirmedAt().isBefore(rule.getEffectiveAt().toInstant(java.time.ZoneOffset.UTC)))continue;
                long decisionStarted = QuoteTiming.start();
                boolean reached=TargetPriceMatcher.matches(TargetPriceMatcher.Definition.from(rule),rule.getStatus(),price);
                QuoteTiming.finish("rule_decision", decisionStarted);
                if (reached) {
                    AlertRuleStatus target = AlertRuleStatus.COMPLETED;
                    LocalDateTime triggeredAt=eventTime;
                    if(!openingCross(rule,price,opening)
                            && (confirmation.confirmation().equals("RECOVERY")
                                ||rule.getEffectiveAt()!=null&&eventTime.isBefore(rule.getEffectiveAt())))
                        triggeredAt=com.ezra.pricepulse.market.MarketTime.utc(confirmation.confirmedAt());
                    requireCompletion(rule, triggeredAt);
                    long triggerStarted = QuoteTiming.start();
                    try { pending.add(recordTrigger(rule, quoteId, price, triggeredAt, opening,confirmation)); }
                    finally { QuoteTiming.finish("trigger_outbox_write", triggerStarted); }
                    rule.setStatus(target);
                    rule.setLastTriggeredAt(triggeredAt);
                    rule.setVersion(rule.getVersion() + 1);
                    changed.add(rule);
                    triggered++;
                }
            }
            if (!pending.isEmpty()) {
                long outboxStarted = QuoteTiming.start();
                try { outbox.alertTriggeredBatch(pending); }
                finally { QuoteTiming.finish("trigger_outbox_write", outboxStarted); }
            }
        }
        if (!changed.isEmpty()) {
            index.changed(stock, changed);
        }
        return new MatchingResult(ids.size(), triggered);
    }

    /** Caller holds the stock row lock and updates the derived index once with the final rule state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmOnceRule(Stock stock,AlertRule rule,long quoteId,BigDecimal price,LocalDateTime confirmedAt,TargetReachContext confirmation){
        if(rule.getStockId()!=stock.id()||!TargetPriceMatcher.matches(TargetPriceMatcher.Definition.from(rule),rule.getStatus(),price))return false;
        requireCompletion(rule,confirmedAt);
        outbox.alertTriggeredBatch(List.of(recordTrigger(rule,quoteId,price,confirmedAt,null,confirmation)));
        rule.setStatus(AlertRuleStatus.COMPLETED);rule.setLastTriggeredAt(confirmedAt);rule.setVersion(rule.getVersion()+1);
        return true;
    }

    @Transactional(readOnly = true)
    public List<AlertTriggerResponse> history(long userId, long ruleId, int limit) {
        return triggers.listOwned(userId, ruleId, limit).stream().map(trigger -> {
            try {
                return new AlertTriggerResponse(trigger.getId(), trigger.getRuleId(),
                        trigger.getQuoteEventId(), trigger.getTriggerPrice(), trigger.getEventTime(),
                        json.readTree(trigger.getRuleSnapshot()), trigger.getCreatedAt(),
                        trigger.getTriggerContext()==null ? null : json.readTree(trigger.getTriggerContext()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Cannot read rule snapshot", exception);
            }
        }).toList();
    }

    public void requireObservedRule(long userId, long ruleId, long stockId) {
        AlertRule rule = rules.findOwned(ruleId, userId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Rule was not found");
        }
        if (rule.getStockId() != stockId) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Observed rule must belong to this stock");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AlertRule observeRule(long userId, long ruleId, long stockId) {
        AlertRule rule = rules.findOwned(ruleId, userId);
        return rule != null && rule.getStockId() == stockId ? rule : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RuleObservation observation(AlertRule before, long quoteId) {
        if (before == null) return null;
        AlertRule after = observeRule(before.getUserId(), before.getId(), before.getStockId());
        if (after == null) return null;
        // The stock row lock is shared by matching and rule edits: both reads describe this quote's transaction.
        Long triggerId = before.getStatus() == AlertRuleStatus.ACTIVE
                && after.getStatus() == AlertRuleStatus.COMPLETED
                ? triggers.findIdForQuote(before.getId(), quoteId) : null;
        return new RuleObservation(before.getId(),before.getName(),before.getDirection(),before.getThresholdPrice(),
                before.getStatus(),after.getStatus(),triggerId!=null?"TRIGGERED":"NONE",triggerId,after.getVersion());
    }

    private void requireCompletion(AlertRule rule, LocalDateTime triggeredAt) {
        if (rules.complete(rule.getId(),rule.getVersion(),triggeredAt) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "Rule changed during matching; retry quote");
        }
    }

    private OutboxWriter.PendingAlert recordTrigger(AlertRule rule, long quoteId, BigDecimal price, LocalDateTime eventTime, OpeningGapContext opening,TargetReachContext confirmation) {
        AlertTrigger trigger = new AlertTrigger();
        trigger.setTriggerKey("rule:" + rule.getId() + ":version:" + rule.getVersion());
        trigger.setRuleId(rule.getId());
        trigger.setQuoteEventId(quoteId);
        trigger.setTriggerPrice(price);
        trigger.setEventTime(eventTime);
        try {
            trigger.setRuleSnapshot(json.writeValueAsString(AlertRuleResponse.from(rule)));
            if(openingCross(rule,price,opening))trigger.setTriggerContext(json.writeValueAsString(opening.forRule(rule.getEffectiveAt())));
            else trigger.setTriggerContext(json.writeValueAsString(confirmation));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize rule snapshot", exception);
        }
        triggers.insert(trigger);
        return new OutboxWriter.PendingAlert(trigger.getId(), rule.getUserId(), rule.getId(), rule.getStockId());
    }
    private boolean openingCross(AlertRule rule,BigDecimal price,OpeningGapContext opening){
        return opening!=null && opening.eligible(rule.getEffectiveAt()) && opening.eligible(rule.getCreatedAt())
                && !TargetPriceMatcher.reached(TargetPriceMatcher.Definition.from(rule),new BigDecimal(opening.referencePrice()))
                && TargetPriceMatcher.reached(TargetPriceMatcher.Definition.from(rule),price);
    }
}
