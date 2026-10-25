package com.ezra.pricepulse.auth;

import java.time.Instant;

public record AuthResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UserView user) {

    public record UserView(long id, String email, String displayName) {
    }
}
