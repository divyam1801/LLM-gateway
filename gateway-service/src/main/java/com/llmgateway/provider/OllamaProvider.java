package com.llmgateway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private static final Set<String> SUPPORTED_MODELS = Set.of(
            "llama3", "llama3.1", "llama3.2", "mistral", "codellama", "gemma2"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OllamaProvider(@Value("${gateway.ollama.base-url}") String baseUrl,
                          ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public boolean supportsModel(String model) {
        return SUPPORTED_MODELS.contains(model);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Map<String, Object> ollamaRequest = buildOllamaRequest(request, false);

        JsonNode response = webClient.post()
                .uri("/api/chat")
                .bodyValue(ollamaRequest)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        return convertToChatResponse(response, request.model());
    }

    @Override
    public Flux<String> stream(ChatRequest request) {
        Map<String, Object> ollamaRequest = buildOllamaRequest(request, true);

        String responseId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);

        return webClient.post()
                .uri("/api/chat")
                .bodyValue(ollamaRequest)
                .accept(MediaType.APPLICATION_NDJSON)
                .retrieve()
                .bodyToFlux(String.class)
                .map(line -> convertToSseChunk(line, responseId, request.model()))
                .filter(chunk -> chunk != null);
    }

    private Map<String, Object> buildOllamaRequest(ChatRequest request, boolean stream) {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> req = new java.util.HashMap<>();
        req.put("model", request.model());
        req.put("messages", messages);
        req.put("stream", stream);

        if (request.temperature() != null) {
            req.put("options", Map.of("temperature", request.temperature()));
        }

        return req;
    }

    private ChatResponse convertToChatResponse(JsonNode response, String model) {
        String content = response.path("message").path("content").asText("");
        int promptTokens = response.path("prompt_eval_count").asInt(0);
        int completionTokens = response.path("eval_count").asInt(0);

        return new ChatResponse(
                "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8),
                "chat.completion",
                Instant.now().getEpochSecond(),
                model,
                List.of(new ChatResponse.Choice(
                        0,
                        new ChatResponse.Message("assistant", content),
                        "stop"
                )),
                new ChatResponse.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens)
        );
    }

    private String convertToSseChunk(String line, String responseId, String model) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String content = node.path("message").path("content").asText("");
            boolean done = node.path("done").asBoolean(false);

            Map<String, Object> delta = new java.util.HashMap<>();
            if (!content.isEmpty()) {
                delta.put("content", content);
            }

            Map<String, Object> choice = Map.of(
                    "index", 0,
                    "delta", delta,
                    "finish_reason", done ? "stop" : null
            );

            Map<String, Object> chunk = Map.of(
                    "id", responseId,
                    "object", "chat.completion.chunk",
                    "created", Instant.now().getEpochSecond(),
                    "model", model,
                    "choices", List.of(choice)
            );

            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert Ollama stream chunk", e);
            return null;
        }
    }
}
