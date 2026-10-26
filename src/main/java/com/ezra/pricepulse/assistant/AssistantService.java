package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!standalone & !mq-it")
public class AssistantService {
    private final AlertDraftGenerator generator;
    private final StockMapper stockMapper;

    public AssistantService(AlertDraftGenerator generator, StockMapper stockMapper) {
        this.generator = generator;
        this.stockMapper = stockMapper;
    }

    public AlertDraftResponse draft(AlertDraftRequest request) {
        ModelAlertDraft modelDraft = generator.generate(request.text().trim());
        if (!modelDraft.complete()) {
            return AlertDraftResponse.clarification(questionOrDefault(modelDraft.clarificationQuestion()));
        }

        String symbol = requiredText(modelDraft.symbol(), "stock symbol").toUpperCase(Locale.ROOT);
        String market = optionalText(modelDraft.market(), 16, null);
        var stocks = stockMapper.findActiveBySymbol(symbol, market == null ? null : market.toUpperCase(Locale.ROOT));
        if (stocks.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "没有找到代码为 " + symbol + " 的可用股票，请从股票列表确认后重试");
        }
        if (stocks.size() > 1) {
            return AlertDraftResponse.clarification("这个股票代码在多个市场存在，请在描述中补充市场代码。");
        }
        Stock stock = stocks.getFirst();

        BigDecimal threshold = parsePrice(modelDraft.thresholdPrice(), "thresholdPrice");
        AlertDirection direction = require(modelDraft.direction(), "direction");
        AlertTriggerMode triggerMode = require(modelDraft.triggerMode(), "triggerMode");
        BigDecimal rearmPrice = null;
        if (triggerMode == AlertTriggerMode.REPEAT) {
            if (modelDraft.rearmPrice() == null || modelDraft.rearmPrice().isBlank()) {
                return AlertDraftResponse.clarification(
                        "这是重复提醒。价格需要回到多少后，才允许下一次提醒？");
            }
            rearmPrice = parsePrice(modelDraft.rearmPrice(), "rearmPrice");
            boolean valid = direction == AlertDirection.ABOVE
                    ? rearmPrice.compareTo(threshold) < 0
                    : rearmPrice.compareTo(threshold) > 0;
            if (!valid) {
                String relation = direction == AlertDirection.ABOVE ? "低于" : "高于";
                return AlertDraftResponse.clarification(
                        "重新激活价需要" + relation + "触发价，请换一个价格。");
            }
        }

        String defaultName = stock.symbol() + " 价格提醒";
        String name = optionalText(modelDraft.name(), 100, defaultName);
        String note = optionalText(modelDraft.note(), 500, null);
        return AlertDraftResponse.ready(new AlertRuleDraft(
                stock.id(), stock.symbol(), stock.name(), name, note, direction,
                threshold, triggerMode, rearmPrice));
    }

    private BigDecimal parsePrice(String raw, String field) {
        try {
            BigDecimal price = new BigDecimal(requiredText(raw, field));
            try {
                return PriceTicks.normalize(price, field);
            } catch (BusinessException exception) {
                throw invalidModelOutput("价格必须大于零、在支持范围内且最多四位小数");
            }
        } catch (NumberFormatException exception) {
            throw invalidModelOutput("价格不是有效数字");
        }
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidModelOutput(field + " is missing");
        }
        return value.trim();
    }

    private <T> T require(T value, String field) {
        if (value == null) {
            throw invalidModelOutput(field + " is missing");
        }
        return value;
    }

    private String optionalText(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : fallback;
    }

    private String questionOrDefault(String value) {
        return optionalText(value, 300, "请补充股票代码、触发方向、价格和提醒次数。");
    }

    private BusinessException invalidModelOutput(String detail) {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "AI 返回的草稿无法通过校验（" + detail + "），请重试或手动创建规则");
    }
}
