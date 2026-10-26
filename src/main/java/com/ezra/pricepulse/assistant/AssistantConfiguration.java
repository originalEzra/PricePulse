package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("!standalone & !mq-it")
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "price-pulse.assistant", name = "enabled", havingValue = "true")
    AlertDraftGenerator providerAlertDraftGenerator(AssistantProperties properties) {
        requireConfigured(properties.getApiKey(), "PRICEPULSE_AI_API_KEY");
        requireConfigured(properties.getBaseUrl(), "PRICEPULSE_AI_BASE_URL");
        requireConfigured(properties.getModel(), "PRICEPULSE_AI_MODEL");

        var converter = new BeanOutputConverter<>(ModelAlertDraft.class);
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());

        var api = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        var options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, converter.getJsonSchema()))
                .build();
        var model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        return new ProviderAlertDraftGenerator(model);
    }

    @Bean
    @ConditionalOnMissingBean(AlertDraftGenerator.class)
    AlertDraftGenerator disabledAlertDraftGenerator() {
        return userText -> {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    "AI 草稿功能尚未启用，请先配置模型服务；你仍可手动创建提醒规则");
        };
    }

    private void requireConfigured(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("启用 AI 草稿功能时必须配置 " + environmentName);
        }
    }
}
