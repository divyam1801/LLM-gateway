package com.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateResponse(
        List<Candidate> candidates,
        UsageMetadata usageMetadata
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            Content content,
            String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String role, List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageMetadata(
            int promptTokenCount,
            int candidatesTokenCount,
            int totalTokenCount
    ) {}

    public String extractResponseText() {
        if (candidates == null || candidates.isEmpty()) return "";
        Candidate first = candidates.getFirst();
        if (first.content() == null || first.content().parts() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part part : first.content().parts()) {
            if (part.text() != null) sb.append(part.text());
        }
        return sb.toString();
    }
}
