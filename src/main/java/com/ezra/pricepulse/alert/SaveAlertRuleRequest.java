package com.ezra.pricepulse.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SaveAlertRuleRequest(
        @NotNull @Positive Long stockId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String note,
        @NotNull AlertDirection direction,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal thresholdPrice) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectRemovedField(String name,com.fasterxml.jackson.databind.JsonNode value){
        throw new IllegalArgumentException("Unsupported rule field: "+name+"; only one-time target rules are supported");
    }
}
