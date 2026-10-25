package com.ezra.pricepulse.alert;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.matching.RuleIndex;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import com.ezra.pricepulse.user.UserMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Profile("!standalone & !mq-it")
public class AlertRuleService {
    private static final List<AlertRuleStatus> EDITABLE_STATUSES =
            List.of(AlertRuleStatus.ACTIVE, AlertRuleStatus.PAUSED);

    private final AlertRuleMapper alertRuleMapper;
    private final StockMapper stockMapper;
    private final UserMapper userMapper;
    private final RuleIndex index;

    public AlertRuleService(AlertRuleMapper alertRuleMapper, StockMapper stockMapper,
            UserMapper userMapper, RuleIndex index) {
        this.alertRuleMapper = alertRuleMapper;
        this.stockMapper = stockMapper;
        this.userMapper = userMapper;
        this.index = index;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertRuleResponse create(long userId, SaveAlertRuleRequest request) {
        requireActiveUser(userId);
        Stock stock = lockStock(request.stockId());
        AlertRule rule = buildRule(userId, null, request);
        alertRuleMapper.insert(rule);
        AlertRule saved = requireOwnedRule(userId, rule.getId());
        index.changed(stock, List.of(saved));
        return AlertRuleResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AlertRuleResponse get(long userId, long ruleId) {
        requireActiveUser(userId);
        return AlertRuleResponse.from(requireOwnedRule(userId, ruleId));
    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> list(
            long userId, Long stockId, String requestedStatus, int limit) {
        requireActiveUser(userId);
        AlertRuleStatus status = parseStatus(requestedStatus);
        if (status == AlertRuleStatus.DELETED) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Deleted rules are not queryable");
        }
        return alertRuleMapper.listOwned(userId, stockId, status, limit).stream()
                .map(AlertRuleResponse::from)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertRuleResponse update(long userId, long ruleId, UpdateAlertRuleRequest request) {
        requireActiveUser(userId);
        LockedRule locked = lockOwnedRule(userId, ruleId, request.version(), request.rule().stockId());
        AlertRule current = locked.rule();
        if (!EDITABLE_STATUSES.contains(current.getStatus())) {
            throw stateConflict("Only active or paused rules can be edited");
        }
        AlertRule replacement = buildRule(userId, ruleId, request.rule());
        int updated = alertRuleMapper.updateDetails(
                replacement, request.version(), EDITABLE_STATUSES);
        requireUpdated(updated);
        AlertRule saved = requireOwnedRule(userId, ruleId);
        locked.stocks().forEach(stock -> index.changed(stock, List.of(saved)));
        return AlertRuleResponse.from(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertRuleResponse pause(long userId, long ruleId, long version) {
        return transition(
                userId, ruleId, version, AlertRuleStatus.PAUSED,
                List.of(AlertRuleStatus.ACTIVE, AlertRuleStatus.COOLDOWN),
                "Only an active or cooldown rule can be paused");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AlertRuleResponse resume(long userId, long ruleId, long version) {
        return transition(
                userId, ruleId, version, AlertRuleStatus.ACTIVE, List.of(AlertRuleStatus.PAUSED),
                "Only a paused rule can be resumed");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(long userId, long ruleId, long version) {
        requireActiveUser(userId);
        LockedRule locked = lockOwnedRule(userId, ruleId, version, null);
        requireUpdated(alertRuleMapper.softDelete(ruleId, userId, version));
        locked.rule().setStatus(AlertRuleStatus.DELETED);
        index.changed(locked.stocks().getFirst(), List.of(locked.rule()));
    }

    private AlertRuleResponse transition(
            long userId,
            long ruleId,
            long version,
            AlertRuleStatus target,
            List<AlertRuleStatus> allowed,
            String invalidStateMessage) {
        requireActiveUser(userId);
        LockedRule locked = lockOwnedRule(userId, ruleId, version, null);
        AlertRule current = locked.rule();
        if (!allowed.contains(current.getStatus())) {
            throw stateConflict(invalidStateMessage);
        }
        int updated = alertRuleMapper.updateStatus(ruleId, userId, version, target, allowed);
        requireUpdated(updated);
        AlertRule saved = requireOwnedRule(userId, ruleId);
        index.changed(locked.stocks().getFirst(), List.of(saved));
        return AlertRuleResponse.from(saved);
    }

    private AlertRule buildRule(long userId, Long ruleId, SaveAlertRuleRequest request) {
        BigDecimal threshold = PriceTicks.normalize(request.thresholdPrice(), "thresholdPrice");
        BigDecimal rearm = request.rearmPrice() == null
                ? null
                : PriceTicks.normalize(request.rearmPrice(), "rearmPrice");
        validateRearm(request.direction(), request.triggerMode(), threshold, rearm);

        var rule = new AlertRule();
        rule.setId(ruleId);
        rule.setUserId(userId);
        rule.setStockId(request.stockId());
        rule.setName(request.name().trim());
        rule.setNote(normalizeNote(request.note()));
        rule.setDirection(request.direction());
        rule.setThresholdPrice(threshold);
        rule.setTriggerMode(request.triggerMode());
        rule.setRearmPrice(rearm);
        return rule;
    }

    private void validateRearm(
            AlertDirection direction,
            AlertTriggerMode mode,
            BigDecimal threshold,
            BigDecimal rearm) {
        if (mode == AlertTriggerMode.ONCE) {
            if (rearm != null) {
                throw new BusinessException(
                        ErrorCode.BUSINESS_RULE_VIOLATION,
                        "A one-time rule must not define rearmPrice");
            }
            return;
        }
        if (rearm == null) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "A repeat rule requires rearmPrice");
        }
        boolean valid = direction == AlertDirection.ABOVE
                ? rearm.compareTo(threshold) < 0
                : rearm.compareTo(threshold) > 0;
        if (!valid) {
            String relation = direction == AlertDirection.ABOVE ? "below" : "above";
            throw new BusinessException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "rearmPrice must be " + relation + " thresholdPrice for this direction");
        }
    }

    private String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }

    private AlertRuleStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AlertRuleStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Unknown rule status: " + value);
        }
    }

    private void requireActiveUser(long userId) {
        if (!userMapper.existsActive(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not active");
        }
    }

    private Stock lockStock(long stockId) {
        Stock stock = stockMapper.findActiveByIdForUpdate(stockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Stock was not found");
        }
        return stock;
    }

    private LockedRule lockOwnedRule(long userId, long ruleId, long version, Long targetStockId) {
        AlertRule beforeLock = requireOwnedRule(userId, ruleId);
        List<Long> ids = new ArrayList<>();
        ids.add(beforeLock.getStockId());
        if (targetStockId != null) {
            ids.add(targetStockId);
        }
        List<Stock> stocks = ids.stream().distinct().sorted().map(this::lockStock).toList();
        AlertRule current = requireOwnedRule(userId, ruleId);
        requireVersion(current, version);
        if (!current.getStockId().equals(beforeLock.getStockId())) {
            throw stateConflict("Alert rule moved to another stock; reload and retry");
        }
        return new LockedRule(current, stocks);
    }

    private record LockedRule(AlertRule rule, List<Stock> stocks) { }

    private AlertRule requireOwnedRule(long userId, long ruleId) {
        AlertRule rule = alertRuleMapper.findOwned(ruleId, userId);
        if (rule == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Alert rule was not found");
        }
        return rule;
    }

    private void requireVersion(AlertRule rule, long version) {
        if (rule.getVersion() != version) {
            throw stateConflict("Alert rule was changed by another request; reload and retry");
        }
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw stateConflict("Alert rule was changed by another request; reload and retry");
        }
    }

    private BusinessException stateConflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
