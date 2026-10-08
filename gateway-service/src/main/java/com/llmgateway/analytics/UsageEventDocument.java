package com.llmgateway.analytics;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document(collection = "usage_events")
public class UsageEventDocument {

    @Id
    private String id;
    private UUID apiKeyId;
    private String provider;
    private String model;
    private String requestType;
    private int inputTokens;
    private int outputTokens;
    private double estimatedCostUsd;
    private long latencyMs;
    private boolean cacheHit;
    private boolean fallbackUsed;
    private String status;
    private String errorMessage;
    private Instant timestamp;

    public String getId() { return id; }
    public UUID getApiKeyId() { return apiKeyId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getRequestType() { return requestType; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public double getEstimatedCostUsd() { return estimatedCostUsd; }
    public long getLatencyMs() { return latencyMs; }
    public boolean isCacheHit() { return cacheHit; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getTimestamp() { return timestamp; }

    public void setApiKeyId(UUID apiKeyId) { this.apiKeyId = apiKeyId; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setModel(String model) { this.model = model; }
    public void setRequestType(String requestType) { this.requestType = requestType; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public void setEstimatedCostUsd(double estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }
    public void setStatus(String status) { this.status = status; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
