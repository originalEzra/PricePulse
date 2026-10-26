package com.ezra.pricepulse.outbox;

import java.util.List;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(prefix = "price-pulse.messaging", name = "enabled", havingValue = "true")
public class RocketMqOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(RocketMqOutboxPublisher.class);

    private final OutboxDispatchService dispatch;
    private final RocketMQTemplate rocketMq;
    private final MessagingProperties properties;

    public RocketMqOutboxPublisher(
            OutboxDispatchService dispatch,
            RocketMQTemplate rocketMq,
            MessagingProperties properties) {
        this.dispatch = dispatch;
        this.rocketMq = rocketMq;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${price-pulse.messaging.publisher-initial-delay-ms:1000}",
            fixedDelayString = "${price-pulse.messaging.publisher-fixed-delay-ms:1000}")
    public void publishAvailable() {
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            int claimed = publishBatch();
            if (claimed < properties.getBatchSize()) {
                return;
            }
        }
    }

    public int publishBatch() {
        List<OutboxEvent> events = dispatch.claimBatch();
        events.forEach(this::publishOne);
        return events.size();
    }

    private void publishOne(OutboxEvent event) {
        try {
            Message<String> message = MessageBuilder.withPayload(event.getPayload())
                    .setHeader(RocketMQHeaders.KEYS, "outbox:" + event.getId())
                    .setHeader("outboxId", event.getId())
                    .setHeader("eventType", event.getEventType())
                    .build();
            SendResult result = rocketMq.syncSend(properties.getTopic(), message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Broker did not acknowledge SEND_OK");
            }
            if (!dispatch.markPublished(event.getId())) {
                log.warn("Outbox {} was sent but its lease was no longer owned; it may be sent again", event.getId());
            }
        } catch (Exception exception) {
            log.warn("Cannot publish outbox {} on attempt {}: {}",
                    event.getId(), event.getAttempts(), exception.getMessage());
            dispatch.markFailed(event, exception);
        }
    }
}
