package com.ezra.pricepulse.phase0;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline compatibility checks; these do not prove that any real model follows the schema. */
class AiStructuredOutputTest {

    @Test
    void generatesSchemaForTheDraftFields() throws Exception {
        var converter = new BeanOutputConverter<>(ProbeDraft.class);
        var schema = new ObjectMapper().readTree(converter.getJsonSchema());

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("symbol")).isTrue();
        assertThat(schema.path("properties").has("threshold")).isTrue();
        assertThat(schema.path("properties").has("direction")).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void convertsAValidJsonDraftAndValidatesIt() {
        var converter = new BeanOutputConverter<>(ProbeDraft.class);
        var draft = converter.convert("""
                {"symbol":"DEMO001","threshold":100.25,"direction":"ABOVE"}
                """);

        assertThat(draft).isNotNull();
        assertThat(draft.symbol()).isEqualTo("DEMO001");
        assertThat(draft.threshold()).isEqualByComparingTo("100.25");
        assertThat(draft.direction()).isEqualTo(Direction.ABOVE);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(draft)).isEmpty();
        }
    }

    @Test
    void rejectsBusinessInvalidDataEvenWhenTheJsonIsValid() {
        var converter = new BeanOutputConverter<>(ProbeDraft.class);
        var draft = converter.convert("""
                {"symbol":"","threshold":-1,"direction":"BELOW"}
                """);

        assertThat(draft).isNotNull();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(draft))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("symbol", "threshold");
        }
    }

    enum Direction {
        ABOVE, BELOW
    }

    record ProbeDraft(
            @JsonProperty(required = true) @NotBlank String symbol,
            @JsonProperty(required = true) @NotNull @Positive BigDecimal threshold,
            @JsonProperty(required = true) @NotNull Direction direction) {
    }
}
