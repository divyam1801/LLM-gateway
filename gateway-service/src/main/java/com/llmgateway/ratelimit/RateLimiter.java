package com.llmgateway.ratelimit;

import java.util.UUID;

public interface RateLimiter {

    RateLimitResult checkRequestLimit(UUID apiKeyId, int maxRequestsPerMinute);

    void recordTokenUsage(UUID apiKeyId, int tokensUsed);

    RateLimitInfo getRateLimitInfo(UUID apiKeyId, int maxRpm, int maxTpm);

    record RateLimitResult(boolean allowed, int remainingRequests, long resetInSeconds) {}

    record RateLimitInfo(
            int limitRequests, int remainingRequests, long resetRequestsInSeconds,
            int limitTokens, int remainingTokens, long resetTokensInSeconds
    ) {}
}
