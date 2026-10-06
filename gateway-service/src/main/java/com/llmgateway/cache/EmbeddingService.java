package com.llmgateway.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient webClient;
    private final String embeddingModel;

    public EmbeddingService(@Value("${gateway.ollama.base-url}") String ollamaBaseUrl,
                            @Value("${gateway.ollama.embedding-model}") String embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.webClient = WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
    }

    public float[] embed(String text) {
        JsonNode response = webClient.post()
                .uri("/api/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", embeddingModel, "prompt", text))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null || !response.has("embedding")) {
            throw new RuntimeException("Failed to get embedding from Ollama");
        }

        JsonNode embeddingNode = response.get("embedding");
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }
}
