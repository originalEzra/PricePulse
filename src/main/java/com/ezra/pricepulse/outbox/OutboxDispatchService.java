package com.ezra.pricepulse.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!standalone & !mq-it")
public class OutboxDispatchService {
    private final OutboxMapper mapper;
    private final MessagingProperties properties;
    private final Clock clock;
    private final String workerId = "publisher-" + UUID.randomUUID();

    public OutboxDispatchService(OutboxMapper mapper, MessagingProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch() {
        mapper.quarantineLegacy();
        LocalDateTime now = now();
        LocalDateTime leaseCutoff = now.minus(properties.getLeaseDuration());
        mapper.failExpiredExhaustedLeases(leaseCutoff, properties.getMaxAttempts(), now);
        List<OutboxEvent> events = mapper.selectClaimable(
                now, leaseCutoff, properties.getMaxAttempts(), properties.getBatchSize());
        if (events.isEmpty()) {
            return List.of();
        }
        List<Long> ids = events.stream().map(OutboxEvent::getId).toList();
        int claimed = mapper.claim(ids, workerId, now);
        if (claimed != events.size()) {
            throw new IllegalStateException("Outbox claim changed fewer rows than were locked");
        }
        events.forEach(event -> {
            event.setStatus(OutboxStatus.SENDING);
            event.setAttempts(event.getAttempts() + 1);
            event.setLockedAt(now);
            event.setLockedBy(workerId);
        });
        return events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(long eventId) {
        return mapper.markPublished(eventId, workerId, now()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, Throwable failure) {
        Duration backoff = backoffFor(event.getAttempts());
        LocalDateTime retryAt = now().plus(backoff);
        return mapper.markFailed(event.getId(), workerId, retryAt, describe(failure)) == 1;
    }

    Duration backoffFor(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 30);
        Duration calculated;
        try {
            calculated = properties.getInitialBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            calculated = properties.getMaxBackoff();
        }
        return calculated.compareTo(properties.getMaxBackoff()) > 0
                ? properties.getMaxBackoff() : calculated;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String describe(Throwable failure) {
        String message = failure.getMessage();
        String result = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        result = result.replace('\n', ' ').replace('\r', ' ');
        return result.length() <= 1000 ? result : result.substring(0, 1000);
    }
}
