package com.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateRequest(
        List<Content> contents,
        Content systemInstruction,
        GenerationConfig generationConfig
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String role, List<Part> parts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerationConfig(
            Double temperature,
            Integer maxOutputTokens,
            Double topP,
            Integer topK
    ) {}

    public String extractPromptText() {
        if (contents == null || contents.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Content content : contents) {
            if (content.parts() == null) continue;
            for (Part part : content.parts()) {
                if (part.text() != null) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(part.text());
                }
            }
        }
        return sb.toString();
    }
}
