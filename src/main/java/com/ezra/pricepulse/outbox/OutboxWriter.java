package com.ezra.pricepulse.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!standalone & !mq-it")
public class OutboxWriter {
    private static final int MAX_BATCH = 500;
    public record PendingAlert(long triggerId, long userId, long ruleId, long stockId) { }
    public record AlertPayload(int position, long triggerId, String payload) { }
    private final OutboxMapper mapper;
    private final ObjectMapper json;

    public OutboxWriter(OutboxMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void alertTriggered(long triggerId, long userId, long ruleId, long stockId) {
        try {
            int inserted = mapper.insertAlertTriggered(triggerId, json.writeValueAsString(Map.of(
                    "schemaVersion", 1, "triggerId", triggerId, "userId", userId,
                    "ruleId", ruleId, "stockId", stockId)));
            if (inserted != 1) throw new IllegalStateException("Only persisted REAL triggers may create Outbox events");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize alert event", exception);
        }
    }

    /** A batching boundary, not a transaction boundary. Single alerts keep the existing SQL path. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void alertTriggeredBatch(List<PendingAlert> alerts) {
        if (alerts.size() == 1) {
            var one = alerts.getFirst();
            alertTriggered(one.triggerId(), one.userId(), one.ruleId(), one.stockId());
            return;
        }
        for (int offset = 0; offset < alerts.size(); offset += MAX_BATCH) {
            var payloads = new ArrayList<AlertPayload>();
            for (int i = offset; i < Math.min(offset + MAX_BATCH, alerts.size()); i++) {
                var alert = alerts.get(i);
                try {
                    payloads.add(new AlertPayload(i - offset, alert.triggerId(), json.writeValueAsString(Map.of(
                            "schemaVersion", 1, "triggerId", alert.triggerId(), "userId", alert.userId(),
                            "ruleId", alert.ruleId(), "stockId", alert.stockId()))));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Cannot serialize alert event", exception);
                }
            }
            if (mapper.insertAlertTriggeredBatch(payloads) != payloads.size()) {
                throw new IllegalStateException("Only persisted REAL triggers may create every Outbox row");
            }
        }
    }
}
