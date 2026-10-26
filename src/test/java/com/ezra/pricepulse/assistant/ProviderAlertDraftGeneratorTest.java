package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProviderAlertDraftGeneratorTest {
    private MockRestServiceServer server;
    private ProviderAlertDraftGenerator generator;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var api = OpenAiApi.builder().baseUrl("https://model.example.test")
                .apiKey("test-placeholder").restClientBuilder(builder).build();
        var converter = new BeanOutputConverter<>(ModelAlertDraft.class);
        var options = OpenAiChatOptions.builder().model("test-model")
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, converter.getJsonSchema()))
                .build();
        generator = new ProviderAlertDraftGenerator(OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(options).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build());
    }

    @Test
    void convertsAnOpenAiCompatibleResponseAndKeepsUserTextInItsOwnMessage() throws Exception {
        String userText = "DEMO001 上破 100 提醒一次";
        String content = """
                {"complete":true,"clarificationQuestion":"","symbol":"DEMO001","market":"",
                 "direction":"ABOVE","thresholdPrice":"100","triggerMode":"ONCE",
                 "rearmPrice":"","name":"突破提醒","note":""}
                """;
        server.expect(requestTo("https://model.example.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value(userText))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andRespond(withSuccess(response(content), MediaType.APPLICATION_JSON));

        var draft = generator.generate(userText);

        assertThat(draft.complete()).isTrue();
        assertThat(draft.direction()).isEqualTo(AlertDirection.ABOVE);
        assertThat(draft.thresholdPrice()).isEqualTo("100");
        server.verify();
    }

    @Test
    void mapsMalformedModelOutputToAnUnavailableError() throws Exception {
        server.expect(requestTo("https://model.example.test/v1/chat/completions"))
                .andRespond(withSuccess(response("not a JSON draft"), MediaType.APPLICATION_JSON));

        assertUnavailable();
        server.verify();
    }

    @Test
    void mapsAReadTimeoutWithoutRetryingTheProvider() {
        server.expect(requestTo("https://model.example.test/v1/chat/completions"))
                .andRespond(withException(new SocketTimeoutException("test timeout")));

        assertUnavailable();
        server.verify();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> generator.generate("DEMO001 上破 100 提醒一次"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private String response(String content) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "id", "test-response", "object", "chat.completion", "created", 0,
                "model", "test-model", "choices", List.of(Map.of("index", 0,
                        "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content)))));
    }
}
