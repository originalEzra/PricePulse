package com.ezra.pricepulse.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AlertDraftRequest(@NotBlank @Size(max = 1000) String text) {
}
