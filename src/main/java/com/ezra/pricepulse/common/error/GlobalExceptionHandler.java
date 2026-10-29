package com.ezra.pricepulse.common.error;

import com.ezra.pricepulse.common.api.ApiError;
import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.common.api.FieldViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.status())
                .body(ApiResponse.failure(ApiError.of(code.name(), exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        List<FieldViolation> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return validationFailure(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        List<FieldViolation> details = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return validationFailure(details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> handleUnreadableMessage() {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.failure(ApiError.of(code.name(), code.defaultMessage())));
    }

    @ExceptionHandler({ServletRequestBindingException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> handleInvalidRequestBinding() {
        return validationFailure(List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNoResourceFound() {
        ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.failure(ApiError.of(code.name(), code.defaultMessage())));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled request failure", exception);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status())
                .body(ApiResponse.failure(ApiError.of(code.name(), code.defaultMessage())));
    }

    private ResponseEntity<ApiResponse<Void>> validationFailure(List<FieldViolation> details) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        var error = new ApiError(code.name(), code.defaultMessage(), details);
        return ResponseEntity.status(code.status()).body(ApiResponse.failure(error));
    }
}
