package com.ezra.pricepulse.auth;

import com.ezra.pricepulse.common.api.ApiError;
import com.ezra.pricepulse.common.api.ApiResponse;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class SecurityErrorWriter {
    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        var body = ApiResponse.failure(ApiError.of(code.name(), message));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
