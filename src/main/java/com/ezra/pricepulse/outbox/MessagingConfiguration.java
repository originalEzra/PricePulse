package com.ezra.pricepulse.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!standalone & !mq-it")
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {
}
