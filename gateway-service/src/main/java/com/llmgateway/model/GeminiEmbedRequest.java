package com.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiEmbedRequest(
        List<EmbedRequestItem> requests
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedRequestItem(
            String model,
            Content content,
            Integer outputDimensionality
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {}

    public List<String> extractInputTexts() {
        if (requests == null) return List.of();
        return requests.stream()
                .filter(r -> r.content() != null && r.content().parts() != null)
                .flatMap(r -> r.content().parts().stream())
                .filter(p -> p.text() != null)
                .map(Part::text)
                .toList();
    }
}
