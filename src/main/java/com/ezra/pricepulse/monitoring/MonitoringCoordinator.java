package com.ezra.pricepulse.monitoring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!standalone & !mq-it")
@ConditionalOnProperty(name="price-pulse.monitoring.enabled",havingValue="true")
public class MonitoringCoordinator {
    private final MonitoringService service;
    public MonitoringCoordinator(MonitoringService service){this.service=service;}
    @Scheduled(fixedDelayString="${price-pulse.monitoring.reconcile-ms:5000}",initialDelayString="${price-pulse.monitoring.reconcile-ms:5000}")
    public void observe(){try{service.reconcile();}catch(RuntimeException ignored){
        // Read views still use live observation and never trust stale persisted CONFIRMED after failure.
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Monitoring observation deferred");
    }}
}
