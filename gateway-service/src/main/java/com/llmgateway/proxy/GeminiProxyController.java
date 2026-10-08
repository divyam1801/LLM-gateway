package com.llmgateway.proxy;

import com.llmgateway.proxy.GeminiRequestParser.RequestType;
import com.llmgateway.proxy.GeminiRequestParser.TokenCounts;
import com.llmgateway.cache.SemanticCache;
import com.llmgateway.events.CostEstimator;
import com.llmgateway.events.KafkaUsagePublisher;
import com.llmgateway.events.UsageEvent;
import com.llmgateway.model.ApiKey;
import com.llmgateway.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/llm-gateway")
public class GeminiProxyController {

    private static final Logger log = LoggerFactory.getLogger(GeminiProxyController.class);
    private static final String PROVIDER_NAME = "gemini";

    private final WebClient webClient;
    private final GeminiRequestParser parser;
    private final SemanticCache semanticCache;
    private final RateLimiter rateLimiter;
    private final KafkaUsagePublisher kafkaPublisher;
    private final CostEstimator costEstimator;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;

    public GeminiProxyController(
            @Value("${gateway.gemini.base-url}") String geminiBaseUrl,
            @Value("${gateway.gemini.api-key}") String geminiApiKey,
            GeminiRequestParser parser,
            SemanticCache semanticCache,
            RateLimiter rateLimiter,
            KafkaUsagePublisher kafkaPublisher,
            CostEstimator costEstimator,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .baseUrl(geminiBaseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.geminiApiKey = geminiApiKey;
        this.parser = parser;
        this.semanticCache = semanticCache;
        this.rateLimiter = rateLimiter;
        this.kafkaPublisher = kafkaPublisher;
        this.costEstimator = costEstimator;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/**")
    public ResponseEntity<?> proxy(@RequestBody byte[] body, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        String fullPath = request.getRequestURI();
        String forwardPath = fullPath.replaceFirst("/llm-gateway", "");
        String model = parser.extractModel(forwardPath);
        RequestType requestType = parser.extractRequestType(forwardPath);
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");

        log.info("Proxy request: path={}, model={}, type={}", forwardPath, model, requestType);

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(PROVIDER_NAME);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            log.warn("Circuit breaker OPEN for {} — rejecting request", PROVIDER_NAME);
            publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, 0, false, "circuit_open", "Circuit breaker open for gemini");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", Map.of("message", "Gemini provider is currently unavailable", "code", 503)));
        }

        if (requestType == RequestType.CHAT || requestType == RequestType.CHAT_STREAM) {
            String cacheKey = parser.extractCacheKey(body, requestType);
            if (!cacheKey.isBlank()) {
                Optional<byte[]> cached = semanticCache.lookup(cacheKey, model);
                if (cached.isPresent()) {
                    log.info("CACHE HIT — returning cached response for model={}, query='{}...'",
                            model, cacheKey.substring(0, Math.min(80, cacheKey.length())));
                    publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, 0, true, "success", null);

                    if (requestType == RequestType.CHAT_STREAM) {
                        return handleCachedStreamResponse(cached.get(), response);
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-LLM-Gateway-Provider", PROVIDER_NAME)
                            .header("X-LLM-Gateway-Cache", "HIT")
                            .body(cached.get());
                }
                log.info("CACHE MISS — forwarding to Gemini API for model={}, query='{}...'",
                        model, cacheKey.substring(0, Math.min(80, cacheKey.length())));
            }
        }

        if (requestType == RequestType.CHAT_STREAM) {
            handleStreamRequest(body, forwardPath, model, requestType, apiKey, breaker, response);
            return null;
        }

        return handleStandardRequest(body, forwardPath, model, requestType, apiKey, breaker);
    }

    private ResponseEntity<?> handleCachedStreamResponse(byte[] cachedResponse, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("X-LLM-Gateway-Provider", PROVIDER_NAME);
        response.setHeader("X-LLM-Gateway-Cache", "HIT");
        response.setHeader("Cache-Control", "no-cache");
        response.flushBuffer();

        var outputStream = response.getOutputStream();
        outputStream.write(("data: " + new String(cachedResponse, StandardCharsets.UTF_8) + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
        return null;
    }

    private ResponseEntity<?> handleStandardRequest(byte[] body, String forwardPath, String model,
                                                     RequestType requestType, ApiKey apiKey,
                                                     CircuitBreaker breaker) {
        long start = System.currentTimeMillis();
        String cacheKey = parser.extractCacheKey(body, requestType);

        try {
            log.info("Calling Gemini API: model={}, type={}", model, requestType);

            byte[] responseBody = CircuitBreaker.decorateSupplier(breaker, () ->
                    webClient.post()
                            .uri(forwardPath + "?key=" + geminiApiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(byte[].class)
                            .block(Duration.ofSeconds(120))
            ).get();

            long latency = System.currentTimeMillis() - start;
            TokenCounts tokens = parser.extractTokenCounts(responseBody);

            if (apiKey != null && tokens.totalTokens() > 0) {
                rateLimiter.recordTokenUsage(apiKey.getId(), tokens.totalTokens());
            }

            if (requestType == RequestType.CHAT && !cacheKey.isBlank()) {
                semanticCache.store(cacheKey, model, responseBody);
                log.info("CACHE STORE — cached response for model={}, tokens={}", model, tokens.totalTokens());
            }

            publishEvent(apiKey, model, requestType, tokens, latency, false, "success", null);

            log.info("Gemini API response: model={}, tokens={}, latency={}ms, cacheHit=false", model, tokens.totalTokens(), latency);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-LLM-Gateway-Provider", PROVIDER_NAME)
                    .header("X-LLM-Gateway-Cache", "MISS")
                    .body(responseBody);

        } catch (WebClientResponseException e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Gemini API error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, latency, false, "error", e.getMessage());

            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Proxy request failed", e);
            publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, latency, false, "error", e.getMessage());

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", Map.of("message", "Failed to reach Gemini provider", "code", 502)));
        }
    }

    private void handleStreamRequest(byte[] body, String forwardPath, String model,
                                      RequestType requestType, ApiKey apiKey,
                                      CircuitBreaker breaker, HttpServletResponse response) throws IOException {
        long start = System.currentTimeMillis();
        String cacheKey = parser.extractCacheKey(body, requestType);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader("X-LLM-Gateway-Provider", PROVIDER_NAME);
        response.setHeader("X-LLM-Gateway-Cache", "MISS");
        response.setHeader("Cache-Control", "no-cache");
        response.flushBuffer();

        var outputStream = response.getOutputStream();
        int[] totalInput = {0};
        int[] totalOutput = {0};
        StringBuilder responseText = new StringBuilder();

        try {
            log.info("Calling Gemini API (streaming): model={}", model);

            webClient.post()
                    .uri(forwardPath + "?key=" + geminiApiKey + "&alt=sse")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .doOnNext(chunk -> {
                        TokenCounts tokens = parser.extractStreamTokenCounts(chunk);
                        if (tokens.totalTokens() > 0) {
                            totalInput[0] += tokens.inputTokens();
                            totalOutput[0] += tokens.outputTokens();
                            if (apiKey != null) {
                                rateLimiter.recordTokenUsage(apiKey.getId(), tokens.totalTokens());
                            }
                        }
                        extractStreamText(chunk, responseText);
                        try {
                            outputStream.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .doOnError(e -> {
                        long latency = System.currentTimeMillis() - start;
                        log.error("Stream proxy failed", e);
                        publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, latency, false, "error", e.getMessage());
                    })
                    .blockLast(Duration.ofSeconds(120));

            long latency = System.currentTimeMillis() - start;
            TokenCounts aggregated = new TokenCounts(totalInput[0], totalOutput[0], totalInput[0] + totalOutput[0]);
            publishEvent(apiKey, model, requestType, aggregated, latency, false, "success", null);

            if (!cacheKey.isBlank() && responseText.length() > 0) {
                String syntheticResponse = buildSyntheticResponse(responseText.toString(), model);
                semanticCache.store(cacheKey, model, syntheticResponse.getBytes(StandardCharsets.UTF_8));
                log.info("CACHE STORE — cached streaming response for model={}, tokens={}", model, aggregated.totalTokens());
            }

            log.info("Gemini API stream complete: model={}, tokens={}, latency={}ms, cacheHit=false", model, aggregated.totalTokens(), latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Stream proxy failed", e);
            publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, latency, false, "error", e.getMessage());
        }
    }

    private void extractStreamText(String chunk, StringBuilder sb) {
        try {
            JsonNode node = objectMapper.readTree(chunk);
            JsonNode candidates = node.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        String text = part.path("text").asText(null);
                        if (text != null) {
                            sb.append(text);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String buildSyntheticResponse(String fullText, String model) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" +
                objectMapper.valueToTree(fullText).toString() +
                "}],\"role\":\"model\"},\"finishReason\":\"STOP\"}],\"modelVersion\":\"" +
                model + "\"}";
    }

    private void publishEvent(ApiKey apiKey, String model, RequestType requestType,
                              TokenCounts tokens, long latency, boolean cacheHit,
                              String status, String errorMessage) {
        try {
            double cost = cacheHit ? 0.0 : costEstimator.estimate(PROVIDER_NAME, model, tokens.inputTokens(), tokens.outputTokens());
            kafkaPublisher.publish(UsageEvent.builder()
                    .apiKeyId(apiKey != null ? apiKey.getId() : null)
                    .provider(PROVIDER_NAME)
                    .model(model)
                    .requestType(requestType.name().toLowerCase())
                    .inputTokens(tokens.inputTokens())
                    .outputTokens(tokens.outputTokens())
                    .estimatedCostUsd(cost)
                    .latencyMs(latency)
                    .cacheHit(cacheHit)
                    .fallbackUsed(false)
                    .status(status)
                    .errorMessage(errorMessage)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish usage event", e);
        }
    }
}
