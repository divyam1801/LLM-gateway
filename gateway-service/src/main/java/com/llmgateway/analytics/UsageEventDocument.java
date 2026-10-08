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
}
