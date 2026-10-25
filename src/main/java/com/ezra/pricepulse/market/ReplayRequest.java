package com.ezra.pricepulse.market;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplayRequest(
        @NotEmpty @Size(max = 1000) List<@Valid QuoteInputRequest> events) {
}
