package com.ezra.pricepulse.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!standalone & !mq-it")
public class OutboxWriter {
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
}
