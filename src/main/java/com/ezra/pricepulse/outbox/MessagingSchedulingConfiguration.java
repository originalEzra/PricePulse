package com.ezra.pricepulse.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(prefix = "price-pulse.messaging", name = "enabled", havingValue = "true")
public class MessagingSchedulingConfiguration {
}
