package com.ezra.pricepulse.alert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateAlertRuleRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Valid SaveAlertRuleRequest rule) {
}
