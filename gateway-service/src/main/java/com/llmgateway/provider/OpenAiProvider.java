package com.llmgateway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.model.ChatRequest;
import com.llmgateway.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Set;

@Component
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);
    private static final Set<String> SUPPORTED_MODELS = Set.of(
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiProvider(@Value("${gateway.openai.base-url}") String baseUrl,
                          @Value("${gateway.openai.api-key}") String apiKey,
                          ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public boolean supportsModel(String model) {
        return SUPPORTED_MODELS.contains(model);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class)
                .block();
    }

    @Override
    public Flux<String> stream(ChatRequest request) {
        ChatRequest streamRequest = new ChatRequest(
                request.model(), request.messages(), request.temperature(),
                request.maxTokens(), true, request.topP(),
                request.frequencyPenalty(), request.presencePenalty()
        );

        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(streamRequest)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class);
    }
}
