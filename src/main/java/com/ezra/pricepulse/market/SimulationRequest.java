package com.ezra.pricepulse.market;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SimulationRequest(
        @Positive long stockId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,50}") String runKey,
        @Min(1) @Max(1000) int steps,
        @NotNull SimulationMode mode,
        long seed,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("0.2") BigDecimal stepRatio,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal initialPrice,
        @NotNull LocalDateTime startTime) {
}
