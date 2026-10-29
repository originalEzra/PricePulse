package com.ezra.pricepulse.market;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MarketProperties.class, AlpacaProperties.class})
public class MarketConfiguration {
}
