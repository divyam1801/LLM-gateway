package com.llmgateway.cache;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient webClient;
    private final String apiKey;
    private final String embeddingModel;
    private final int vectorDimensions;

    public EmbeddingService(@Value("${gateway.gemini.base-url}") String geminiBaseUrl,
                            @Value("${gateway.gemini.api-key}") String apiKey,
                            @Value("${gateway.gemini.embedding-model:gemini-embedding-001}") String embeddingModel,
                            @Value("${gateway.cache.vector-dimensions:768}") int vectorDimensions) {
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
        this.vectorDimensions = vectorDimensions;
        this.webClient = WebClient.builder()
                .baseUrl(geminiBaseUrl)
                .build();
    }

    public float[] embed(String text) {
        Map<String, Object> body = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", vectorDimensions
        );

        JsonNode response = webClient.post()
                .uri("/v1/models/{model}:embedContent?key={key}", embeddingModel, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null || !response.has("embedding") || !response.get("embedding").has("values")) {
            throw new RuntimeException("Failed to get embedding from Gemini");
        }

        JsonNode values = response.get("embedding").get("values");
        float[] embedding = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            embedding[i] = (float) values.get(i).asDouble();
        }
        return embedding;
    }
}
