package com.ezra.pricepulse.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("price-pulse.messaging")
@Validated
public class MessagingProperties {
    private boolean enabled;
    @NotBlank
    private String topic = "price-pulse-alert-triggered";
    @NotBlank
    private String consumerGroup = "price-pulse-notification-consumer";
    @Min(1)
    private int batchSize = 50;
    @Min(1)
    private int maxBatchesPerRun = 4;
    @Min(1)
    private int maxAttempts = 8;
    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(30);
    @NotNull
    private Duration initialBackoff = Duration.ofSeconds(2);
    @NotNull
    private Duration maxBackoff = Duration.ofMinutes(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
    public void setMaxBatchesPerRun(int maxBatchesPerRun) { this.maxBatchesPerRun = maxBatchesPerRun; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
}
