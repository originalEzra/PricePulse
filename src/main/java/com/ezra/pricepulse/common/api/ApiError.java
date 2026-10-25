package com.ezra.pricepulse.common.api;

import java.util.List;

public record ApiError(String code, String message, List<FieldViolation> details) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of());
    }
}
