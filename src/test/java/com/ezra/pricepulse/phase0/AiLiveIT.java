package com.ezra.pricepulse.phase0;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in only: mvn -Pai-live verify. A real call may incur provider charges. */
class AiLiveIT {

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void realProviderReturnsAValidStructuredDraft() {
        String apiKey = requiredEnvironment("PRICEPULSE_AI_API_KEY");
        String modelName = requiredEnvironment("PRICEPULSE_AI_MODEL");
        String baseUrl = System.getenv().getOrDefault("PRICEPULSE_AI_BASE_URL", "https://api.openai.com");
        var converter = new BeanOutputConverter<>(AiStructuredOutputTest.ProbeDraft.class);

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(45));

        var api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        var options = OpenAiChatOptions.builder()
                .model(modelName)
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, converter.getJsonSchema()))
                .build();
        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        var response = model.call(new Prompt("""
                This is a synthetic compatibility test, not a real market request.
                Create a price alert draft for the fictional stock DEMO001.
                The direction is ABOVE and the threshold is exactly 100.
                Return only the JSON object described by the response schema.
                """));

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isNotNull();
        var draft = converter.convert(response.getResult().getOutput().getText());
        assertThat(draft).isNotNull();
        assertThat(draft.symbol()).isEqualTo("DEMO001");
        assertThat(draft.threshold()).isEqualByComparingTo("100");
        assertThat(draft.direction()).isEqualTo(AiStructuredOutputTest.Direction.ABOVE);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(draft)).isEmpty();
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The ai-live profile requires environment variable " + name);
        }
        return value;
    }
}
