package com.llmgateway.controller;

import com.llmgateway.analytics.UsageEventDocument;
import com.llmgateway.analytics.UsageRepository;
import com.llmgateway.auth.ApiKeyService;
import com.llmgateway.cache.SemanticCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class StatsController {

    private final UsageRepository usageRepository;
    private final ApiKeyService apiKeyService;
    private final SemanticCache semanticCache;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public StatsController(UsageRepository usageRepository,
                           ApiKeyService apiKeyService,
                           SemanticCache semanticCache,
                           CircuitBreakerRegistry circuitBreakerRegistry) {
        this.usageRepository = usageRepository;
        this.apiKeyService = apiKeyService;
        this.semanticCache = semanticCache;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats(@RequestParam(defaultValue = "7") int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        List<UsageEventDocument> events = usageRepository
                .findByTimestampAfterOrderByTimestampDesc(since);

        long totalRequests = events.size();
        long errors = events.stream().filter(e -> "error".equals(e.getStatus())).count();
        long rateLimitHits = events.stream().filter(e -> "rate_limited".equals(e.getStatus())).count();

        List<UsageEventDocument> cacheableEvents = events.stream()
                .filter(e -> !"embedding".equals(e.getRequestType()))
                .toList();

        long cacheableRequests = cacheableEvents.size();
        long cacheHits = cacheableEvents.stream().filter(UsageEventDocument::isCacheHit).count();

        double totalCost = events.stream()
                .mapToDouble(UsageEventDocument::getEstimatedCostUsd).sum();

        double cacheSavings = cacheableEvents.stream()
                .filter(UsageEventDocument::isCacheHit)
                .mapToDouble(e -> estimateUncachedCost(e))
                .sum();

        LongSummaryStatistics latencyStats = events.stream()
                .filter(e -> !e.isCacheHit() && !"rate_limited".equals(e.getStatus()))
                .mapToLong(UsageEventDocument::getLatencyMs)
                .summaryStatistics();

        Map<String, Double> costByProvider = events.stream()
                .collect(Collectors.groupingBy(
                        UsageEventDocument::getProvider,
                        Collectors.summingDouble(UsageEventDocument::getEstimatedCostUsd)
                ));

        double cacheHitRate = cacheableRequests > 0 ? (double) cacheHits / cacheableRequests * 100 : 0;
        double errorRate = totalRequests > 0 ? (double) errors / totalRequests * 100 : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("totalRequests", totalRequests);
        result.put("cacheHits", cacheHits);
        result.put("cacheHitRate", Math.round(cacheHitRate * 100.0) / 100.0);
        result.put("totalCost", Math.round(totalCost * 10000.0) / 10000.0);
        result.put("cacheSavings", Math.round(cacheSavings * 10000.0) / 10000.0);
        result.put("errorRate", Math.round(errorRate * 100.0) / 100.0);
        result.put("avgLatencyMs", latencyStats.getCount() > 0 ? latencyStats.getAverage() : 0);
        result.put("costByProvider", costByProvider);
        result.put("activeKeys", apiKeyService.listKeys().stream().filter(k -> k.isEnabled()).count());
        result.put("cacheSize", semanticCache.size());
        result.put("rateLimitHits", rateLimitHits);
        return result;
    }

    @GetMapping("/usage")
    public List<UsageEventDocument> getUsage(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) UUID keyId) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        if (keyId != null) {
            return usageRepository.findByApiKeyIdAndTimestampAfterOrderByTimestampDesc(keyId, since);
        }
        return usageRepository.findByTimestampAfterOrderByTimestampDesc(since);
    }

    @GetMapping("/providers/health")
    public List<Map<String, Object>> getProviders() {
        Map<String, CircuitBreaker.State> states = circuitBreakerRegistry.getAllCircuitBreakers()
                .stream().collect(Collectors.toMap(CircuitBreaker::getName, CircuitBreaker::getState));
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);

        List<UsageEventDocument> recentEvents = usageRepository
                .findByTimestampAfterOrderByTimestampDesc(since);

        return states.entrySet().stream().map(entry -> {
            String provider = entry.getKey();
            List<UsageEventDocument> providerEvents = recentEvents.stream()
                    .filter(e -> e.getProvider().equals(provider))
                    .toList();

            long totalCalls = providerEvents.size();
            long errorCount = providerEvents.stream()
                    .filter(e -> "error".equals(e.getStatus())).count();
            double errorRate = totalCalls > 0 ? (double) errorCount / totalCalls * 100 : 0;

            long[] latencies = providerEvents.stream()
                    .mapToLong(UsageEventDocument::getLatencyMs)
                    .sorted().toArray();

            return Map.<String, Object>of(
                    "provider", provider,
                    "circuitBreakerState", entry.getValue().toString(),
                    "totalCalls", totalCalls,
                    "errorRate", Math.round(errorRate * 100.0) / 100.0,
                    "p50LatencyMs", percentile(latencies, 50),
                    "p95LatencyMs", percentile(latencies, 95),
                    "p99LatencyMs", percentile(latencies, 99)
            );
        }).toList();
    }

    @PostMapping("/cache/flush")
    public Map<String, String> flushCache() {
        semanticCache.flush();
        return Map.of("status", "cache flushed");
    }

    private double estimateUncachedCost(UsageEventDocument event) {
        return 0.005;
    }

    private long percentile(long[] sortedValues, int p) {
        if (sortedValues.length == 0) return 0;
        int index = (int) Math.ceil(p / 100.0 * sortedValues.length) - 1;
        return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
    }
}
