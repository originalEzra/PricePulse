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
        BigDecimal previous = stock.latestQuoteAt() == null ? null : stock.latestPrice();
        // REPEAT baselines cannot cross; ONCE reachability and cooldown rearming need only the current price.
        if (baseline) previous = price;
        if (opening!=null) previous = new BigDecimal(opening.referencePrice());
        List<Long> ids = index.candidates(stock, previous, price);
        if (baseline || opening!=null) {
            var firstSessionIds=new java.util.LinkedHashSet<>(ids);
            firstSessionIds.addAll(rules.cooldownIds(stock.id()));
            ids=List.copyOf(firstSessionIds);
        }
        if (ids.isEmpty()) {
            return MatchingResult.empty();
        }
        int triggered = 0;
        int rearmed = 0;
        List<AlertRule> changed = new ArrayList<>();
        // Fetch only Redis candidates; MySQL remains the final authority for status and thresholds.
        for (int offset = 0; offset < ids.size(); offset += 500) {
            List<Long> page = ids.subList(offset, Math.min(offset + 500, ids.size()));
            List<OutboxWriter.PendingAlert> pending = new ArrayList<>();
            for (AlertRule rule : rules.findCandidates(stock.id(), page)) {
                if(rule.getTriggerMode()==AlertTriggerMode.ONCE && rule.getStatus()==AlertRuleStatus.ACTIVE
                        && rule.getEffectiveAt()!=null && confirmation.confirmedAt().isBefore(rule.getEffectiveAt().toInstant(java.time.ZoneOffset.UTC)))continue;
                if (opening!=null && rule.getTriggerMode()!=AlertTriggerMode.ONCE && rule.getStatus()==AlertRuleStatus.ACTIVE
                        && (!opening.eligible(rule.getEffectiveAt()) || !opening.eligible(rule.getCreatedAt()))) continue;
                long decisionStarted = QuoteTiming.start();
                var decision = RuleTransitionEngine.evaluateReal(RuleTransitionEngine.Definition.from(rule),
                        rule.getStatus(), previous, price);
                QuoteTiming.finish("rule_decision", decisionStarted);
                if (decision.kind() == RuleTransitionEngine.Kind.TRIGGERED) {
                    AlertRuleStatus target = decision.target();
                    requireTransition(rule, target, eventTime);
                    long triggerStarted = QuoteTiming.start();
                    try { pending.add(recordTrigger(rule, quoteId, price, eventTime, opening,confirmation)); }
                    finally { QuoteTiming.finish("trigger_outbox_write", triggerStarted); }
                    rule.setStatus(target);
                    rule.setLastTriggeredAt(eventTime);
                    rule.setVersion(rule.getVersion() + 1);
                    changed.add(rule);
                    triggered++;
                } else if (decision.kind() == RuleTransitionEngine.Kind.REARMED) {
                    requireTransition(rule, AlertRuleStatus.ACTIVE, null);
                    rule.setStatus(AlertRuleStatus.ACTIVE);
                    rule.setVersion(rule.getVersion() + 1);
                    changed.add(rule);
                    rearmed++;
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
        return new MatchingResult(ids.size(), triggered, rearmed);
    }

    /** Caller holds the stock row lock and updates the derived index once with the final rule state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmOnceRule(Stock stock,AlertRule rule,long quoteId,BigDecimal price,LocalDateTime confirmedAt,TargetReachContext confirmation){
        if(rule.getStockId()!=stock.id()||rule.getTriggerMode()!=AlertTriggerMode.ONCE
                ||RuleTransitionEngine.evaluateReal(RuleTransitionEngine.Definition.from(rule),rule.getStatus(),price,price).kind()!=RuleTransitionEngine.Kind.TRIGGERED)return false;
        requireTransition(rule,AlertRuleStatus.COMPLETED,confirmedAt);
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
                && (after.getStatus() == AlertRuleStatus.COOLDOWN || after.getStatus() == AlertRuleStatus.COMPLETED)
                ? triggers.findIdForQuote(before.getId(), quoteId) : null;
        String event = triggerId != null ? "TRIGGERED"
                : before.getStatus() == AlertRuleStatus.COOLDOWN && after.getStatus() == AlertRuleStatus.ACTIVE
                ? "REARMED" : "NONE";
        return new RuleObservation(before.getId(), before.getName(), before.getDirection(),
                before.getThresholdPrice(), before.getRearmPrice(), before.getTriggerMode(),
                before.getStatus(), after.getStatus(), event, triggerId, after.getVersion());
    }

    private void requireTransition(AlertRule rule, AlertRuleStatus target, LocalDateTime triggeredAt) {
        if (rules.transitionByMatcher(rule.getId(), rule.getVersion(), rule.getStatus(), target, triggeredAt) != 1) {
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
            boolean openingCross=opening!=null && opening.eligible(rule.getEffectiveAt()) && opening.eligible(rule.getCreatedAt())
                    && RuleTransitionEngine.evaluate(RuleTransitionEngine.Definition.from(rule),rule.getStatus(),new BigDecimal(opening.referencePrice()),price).kind()==RuleTransitionEngine.Kind.TRIGGERED;
            if(openingCross)trigger.setTriggerContext(json.writeValueAsString(opening.forRule(rule.getEffectiveAt())));
            else if(rule.getTriggerMode()==AlertTriggerMode.ONCE)trigger.setTriggerContext(json.writeValueAsString(confirmation));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize rule snapshot", exception);
        }
        triggers.insert(trigger);
        return new OutboxWriter.PendingAlert(trigger.getId(), rule.getUserId(), rule.getId(), rule.getStockId());
    }
}
