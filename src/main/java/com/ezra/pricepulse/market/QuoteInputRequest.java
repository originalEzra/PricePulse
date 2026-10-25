package com.ezra.pricepulse.market;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record QuoteInputRequest(
        @NotBlank @Size(max = 128) String eventKey,
        @Positive long stockId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price,
        @NotNull LocalDateTime eventTime,
        @NotBlank @Size(max = 32) String source,
        @PositiveOrZero Long sequenceNo) {
}
