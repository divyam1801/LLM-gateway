package com.llmgateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        Boolean stream,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("frequency_penalty") Double frequencyPenalty,
        @JsonProperty("presence_penalty") Double presencePenalty
) {
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, temperature, maxTokens,
                stream, topP, frequencyPenalty, presencePenalty);
    }

    public boolean isStream() {
        return stream != null && stream;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}
}
