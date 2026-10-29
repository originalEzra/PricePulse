package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in provider test: ./mvnw -Pai-live -Dit.test=AssistantAiLiveIT verify. */
class AssistantAiLiveIT {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void realProviderParsesTheProductionAlertDraftSchema() {
        var draft = generator().generate("DEMO001 涨到 115 时提醒我，只提醒一次。");

        assertThat(draft.complete()).isTrue();
        assertThat(draft.symbol()).isEqualTo("DEMO001");
        assertThat(draft.direction()).isEqualTo(AlertDirection.ABOVE);
        assertThat(new BigDecimal(draft.thresholdPrice())).isEqualByComparingTo("115");
        assertThat(draft.triggerMode()).isEqualTo(AlertTriggerMode.ONCE);
        assertThat(draft.rearmPrice()).isNotNull().isBlank();
        // The name is optional; AssistantService supplies the stock's default alert name when empty.
        assertThat(draft.name()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"001, 001 涨到 115 时提醒我，只提醒一次。", "Technology, Technology 涨到 115 时提醒我，只提醒一次。"})
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void realProviderPreservesPartialReferencesForBusinessResolution(String reference, String text) {
        var draft = generator().generate(text);

        assertThat(draft.complete()).isTrue();
        assertThat(draft.symbol()).isEqualToIgnoringCase(reference);
        assertThat(draft.direction()).isEqualTo(AlertDirection.ABOVE);
        assertThat(new BigDecimal(draft.thresholdPrice())).isEqualByComparingTo("115");
        assertThat(draft.triggerMode()).isEqualTo(AlertTriggerMode.ONCE);
    }

    private AlertDraftGenerator generator() {
        var properties = new AssistantProperties();
        properties.setApiKey(requiredEnvironment("PRICEPULSE_AI_API_KEY"));
        properties.setBaseUrl(System.getenv().getOrDefault("PRICEPULSE_AI_BASE_URL", properties.getBaseUrl()));
        properties.setModel(System.getenv().getOrDefault("PRICEPULSE_AI_MODEL", properties.getModel()));
        return new AssistantConfiguration().providerAlertDraftGenerator(properties);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The ai-live profile requires environment variable " + name);
        }
        return value;
    }
}
