package com.ezra.pricepulse.phase0;

import static org.assertj.core.api.Assertions.assertThat;

import com.ezra.pricepulse.PricePulseApplication;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = PricePulseApplication.class, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "price-pulse.security.jwt.secret=price-pulse-mq-test-jwt-secret-at-least-32-bytes",
        "rocketmq.name-server=${ROCKETMQ_NAMESERVER:127.0.0.1:19876}",
        "rocketmq.producer.group=price-pulse-phase0-it-producer"
})
@ActiveProfiles("mq-it")
class RocketMqIT {
    @Autowired RocketMQTemplate template;
    @Value("${rocketmq.name-server}") String nameserver;

    @Test
    void starterPublishesAndRealConsumerReceives() throws Exception {
        String token = UUID.randomUUID().toString();
        CountDownLatch received = new CountDownLatch(1);
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("phase0-it-" + token);
        consumer.setNamesrvAddr(nameserver);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe("price-pulse-phase0", "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            messages.forEach(message -> {
                if (token.equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
                    received.countDown();
                }
            });
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            // Create the local topic before subscription and avoid an empty-route refresh race.
            assertThat(template.syncSend("price-pulse-phase0", "warmup:" + token).getSendStatus())
                    .isEqualTo(SendStatus.SEND_OK);
            consumer.start();
            assertThat(template.syncSend("price-pulse-phase0", token).getSendStatus())
                    .isEqualTo(SendStatus.SEND_OK);
            assertThat(received.await(30, TimeUnit.SECONDS))
                    .as("the actual broker must deliver the published token")
                    .isTrue();
        } finally {
            consumer.shutdown();
        }
    }
}
