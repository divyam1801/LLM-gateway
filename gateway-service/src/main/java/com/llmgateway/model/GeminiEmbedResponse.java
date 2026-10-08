package com.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiEmbedResponse(
        List<Embedding> embeddings
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Embedding(List<Double> values) {}
}
