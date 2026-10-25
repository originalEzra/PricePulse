package com.ezra.pricepulse.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A valid user identity is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access to this resource is forbidden"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body is malformed"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested resource was not found"),
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with the current resource state"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule validation failed"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required service is unavailable; retry later"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
