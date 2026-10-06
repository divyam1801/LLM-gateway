package com.llmgateway.events;

import java.time.Instant;
import java.util.UUID;

public record UsageEvent(
        UUID apiKeyId,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        double estimatedCostUsd,
        long latencyMs,
        boolean cacheHit,
        boolean fallbackUsed,
        String status,
        String errorMessage,
        Instant timestamp
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID apiKeyId;
        private String provider;
        private String model;
        private int inputTokens;
        private int outputTokens;
        private double estimatedCostUsd;
        private long latencyMs;
        private boolean cacheHit;
        private boolean fallbackUsed;
        private String status = "success";
        private String errorMessage;

        public Builder apiKeyId(UUID val) { apiKeyId = val; return this; }
        public Builder provider(String val) { provider = val; return this; }
        public Builder model(String val) { model = val; return this; }
        public Builder inputTokens(int val) { inputTokens = val; return this; }
        public Builder outputTokens(int val) { outputTokens = val; return this; }
        public Builder estimatedCostUsd(double val) { estimatedCostUsd = val; return this; }
        public Builder latencyMs(long val) { latencyMs = val; return this; }
        public Builder cacheHit(boolean val) { cacheHit = val; return this; }
        public Builder fallbackUsed(boolean val) { fallbackUsed = val; return this; }
        public Builder status(String val) { status = val; return this; }
        public Builder errorMessage(String val) { errorMessage = val; return this; }

        public UsageEvent build() {
            return new UsageEvent(apiKeyId, provider, model, inputTokens, outputTokens,
                    estimatedCostUsd, latencyMs, cacheHit, fallbackUsed, status,
                    errorMessage, Instant.now());
        }
    }
}
