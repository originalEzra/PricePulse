package com.ezra.pricepulse.notification;

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

    public AlertTriggeredConsumer(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void onMessage(String payload) {
        notifications.consume(payload);
    }
}
