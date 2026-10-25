package com.ezra.pricepulse.common;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PriceTicks {
    // Four bands below 2^52 keep every scaled price exact in Redis double scores.
    public static final long BAND = 1_000_000_000_000_000L;
    public static final BigDecimal MAX_PRICE = new BigDecimal("99999999999.9999");

    private PriceTicks() { }

    public static BigDecimal normalize(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_PRICE) > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    field + " must be positive and at most " + MAX_PRICE);
        }
        try {
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    field + " supports at most 4 decimal places");
        }
    }

    public static long ticks(BigDecimal value) {
        return normalize(value, "price").movePointRight(4).longValueExact();
    }
}
