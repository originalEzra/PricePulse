package com.ezra.pricepulse.market;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("price-pulse.market")
public record MarketProperties(Duration liveMaxAge, Duration maxFutureSkew) {
    public MarketProperties {
        if (liveMaxAge == null || liveMaxAge.isNegative() || liveMaxAge.isZero()) {
            throw new IllegalArgumentException("liveMaxAge must be positive");
        }
        if (maxFutureSkew == null || maxFutureSkew.isNegative()) {
            throw new IllegalArgumentException("maxFutureSkew must not be negative");
        }
    }
}
