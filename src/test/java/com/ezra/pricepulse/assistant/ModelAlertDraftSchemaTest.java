package com.ezra.pricepulse.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAlertDraftSchemaTest {

    @Test
    void productionDraftSchemaContainsEveryRequiredDecisionField() throws Exception {
        var converter = new BeanOutputConverter<>(ModelAlertDraft.class);
        var schema = new ObjectMapper().readTree(converter.getJsonSchema());

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").fieldNames())
                .toIterable()
                .contains(
                        "complete", "clarificationQuestion", "symbol", "market", "direction",
                        "thresholdPrice", "triggerMode", "rearmPrice", "name", "note");
        assertThat(schema.path("properties").path("symbol").path("description").asText())
                .contains("fragment", "Do not expand");
    }
}
