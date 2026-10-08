package com.llmgateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.events.KafkaUsagePublisher;
import com.llmgateway.events.UsageEvent;
import com.llmgateway.model.ApiKey;
import com.llmgateway.proxy.GeminiRequestParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final KafkaUsagePublisher kafkaPublisher;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper,
                           KafkaUsagePublisher kafkaPublisher) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.kafkaPublisher = kafkaPublisher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        if (apiKey == null) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimiter.RateLimitResult result = rateLimiter.checkRequestLimit(
                apiKey.getId(), apiKey.getRateLimitRpm());

        RateLimiter.RateLimitInfo info = rateLimiter.getRateLimitInfo(
                apiKey.getId(), apiKey.getRateLimitRpm(), apiKey.getRateLimitTpm());

        setRateLimitHeaders(response, info);

        if (!result.allowed()) {
            log.warn("RATE LIMIT HIT — apiKey={}, name={}, limit={} RPM, resetIn={}s",
                    apiKey.getId(), apiKey.getName(), apiKey.getRateLimitRpm(), result.resetInSeconds());

            publishRateLimitEvent(apiKey, request, result.resetInSeconds());

            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(result.resetInSeconds()));
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "error", Map.of(
                            "message", "Rate limit exceeded. Try again in " + result.resetInSeconds() + "s",
                            "type", "rate_limit_error",
                            "code", 429
                    )
            ));
            return;
        }

        log.debug("Rate limit check passed: apiKey={}, remaining={}/{} RPM",
                apiKey.getName(), info.remainingRequests(), info.limitRequests());

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/llm-gateway/");
    }

    private void publishRateLimitEvent(ApiKey apiKey, HttpServletRequest request, long resetInSeconds) {
        try {
            String path = request.getRequestURI().replaceFirst("/llm-gateway", "");
            String model = extractModelFromPath(path);

            kafkaPublisher.publish(UsageEvent.builder()
                    .apiKeyId(apiKey.getId())
                    .provider("gemini")
                    .model(model)
                    .requestType("rate_limited")
                    .inputTokens(0)
                    .outputTokens(0)
                    .estimatedCostUsd(0.0)
                    .latencyMs(0)
                    .cacheHit(false)
                    .fallbackUsed(false)
                    .status("rate_limited")
                    .errorMessage("Rate limit exceeded. Reset in " + resetInSeconds + "s")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish rate limit event", e);
        }
    }

    private String extractModelFromPath(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/models/([^:]+):").matcher(path);
        return m.find() ? m.group(1) : "unknown";
    }

    private void setRateLimitHeaders(HttpServletResponse response, RateLimiter.RateLimitInfo info) {
        response.setHeader("X-RateLimit-Limit-Requests", String.valueOf(info.limitRequests()));
        response.setHeader("X-RateLimit-Remaining-Requests", String.valueOf(info.remainingRequests()));
        response.setHeader("X-RateLimit-Limit-Tokens", String.valueOf(info.limitTokens()));
        response.setHeader("X-RateLimit-Remaining-Tokens", String.valueOf(info.remainingTokens()));
        response.setHeader("X-RateLimit-Reset-Requests", info.resetRequestsInSeconds() + "s");
        response.setHeader("X-RateLimit-Reset-Tokens", info.resetTokensInSeconds() + "s");
    }
}
