package com.ezra.pricepulse.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(prefix = "price-pulse.messaging", name = "enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = "${price-pulse.messaging.topic}",
        consumerGroup = "${price-pulse.messaging.consumer-group}",
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        maxReconsumeTimes = 3,
        awaitTerminationMillisWhenShutdown = 1000)
public class AlertTriggeredConsumer implements RocketMQListener<String> {
    private final NotificationService notifications;
    private final Counter successes;
    private final Counter failures;

    public AlertTriggeredConsumer(NotificationService notifications, MeterRegistry metrics) {
        this.notifications = notifications;
        successes = metrics.counter("pricepulse.notification.deliveries", "outcome", "success");
        failures = metrics.counter("pricepulse.notification.deliveries", "outcome", "failure");
    }

    @Override
    public void onMessage(String payload) {
        try {
            notifications.consume(payload);
            // The service proxy has committed before this counts a delivery; duplicates count as attempts.
            successes.increment();
        } catch (RuntimeException exception) {
            failures.increment();
            throw exception;
        }
    }
}
