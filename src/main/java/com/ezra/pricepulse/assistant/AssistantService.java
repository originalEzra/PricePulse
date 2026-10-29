package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
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

        String reference = requiredText(modelDraft.symbol(), "stock name or code");
        if (reference.length() > 128) {
            return AlertDraftResponse.clarification("股票名称或代码过长，请补充一个简短的名称或代码片段。");
        }
        if (modelDraft.market() != null && modelDraft.market().trim().length() > 16) {
            return AlertDraftResponse.clarification("市场代码过长，请核对股票所属市场后重试。");
        }
        String normalizedInput = request.text().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String normalizedReference = reference.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!normalizedInput.contains(normalizedReference)) {
            return AlertDraftResponse.clarification("还不能确定你指的是哪只股票，请补充名称或代码片段。");
        }
        String market = optionalText(modelDraft.market(), 16, null);
        String normalizedMarket = market == null ? null : market.toUpperCase(Locale.ROOT);
        var exactSymbols = stockMapper.findActiveBySymbol(reference.toUpperCase(Locale.ROOT), normalizedMarket);
        var stocks = exactSymbols.isEmpty() ? resolveNameOrFragment(reference, normalizedMarket) : exactSymbols;
        if (stocks.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "没有找到与“" + reference + "”匹配的可用股票，请补充名称或代码后重试");
        }
        if (stocks.size() > 1) {
            return AlertDraftResponse.clarification(ambiguityQuestion(reference, stocks, !exactSymbols.isEmpty()));
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

    private List<Stock> resolveNameOrFragment(String reference, String market) {
        var exactNames = stockMapper.findActiveByName(reference, market);
        if (!exactNames.isEmpty()) {
            return exactNames;
        }
        String pattern = "%" + reference.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return stockMapper.findActiveByFragment(pattern, market);
    }

    private String ambiguityQuestion(String reference, List<Stock> stocks, boolean exactSymbol) {
        String candidates = stocks.stream().limit(5)
                .map(stock -> stock.symbol() + " · "
                        + (stock.name().length() > 24 ? stock.name().substring(0, 24) + "…" : stock.name())
                        + "（" + stock.market() + "）")
                .collect(Collectors.joining("、"));
        String more = stocks.size() > 5 ? "等" : "";
        return exactSymbol
                ? "这个股票代码在多个市场存在：" + candidates + "。请补充市场代码。"
                : "“" + reference + "”匹配到多只股票：" + candidates + more + "。请补充更完整的名称、代码或市场代码。";
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
