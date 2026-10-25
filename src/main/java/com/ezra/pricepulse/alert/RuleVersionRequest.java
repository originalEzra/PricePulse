package com.ezra.pricepulse.alert;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RuleVersionRequest(@NotNull @PositiveOrZero Long version) {
}
