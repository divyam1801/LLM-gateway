package com.llmgateway.proxy;

import com.llmgateway.proxy.GeminiRequestParser.RequestType;
import com.llmgateway.proxy.GeminiRequestParser.TokenCounts;
import com.llmgateway.events.CostEstimator;
import com.llmgateway.events.KafkaUsagePublisher;
import com.llmgateway.events.UsageEvent;
import com.llmgateway.model.ApiKey;
import com.llmgateway.ratelimit.RateLimiter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/llm-gateway")
public class GeminiProxyController {

    private static final Logger log = LoggerFactory.getLogger(GeminiProxyController.class);
    private static final String PROVIDER_NAME = "gemini";

    private final WebClient webClient;
    private final GeminiRequestParser parser;
    private final RateLimiter rateLimiter;
    private final KafkaUsagePublisher kafkaPublisher;
    private final CostEstimator costEstimator;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final String geminiApiKey;

    public GeminiProxyController(
            @Value("${gateway.gemini.base-url}") String geminiBaseUrl,
            @Value("${gateway.gemini.api-key}") String geminiApiKey,
            GeminiRequestParser parser,
            RateLimiter rateLimiter,
            KafkaUsagePublisher kafkaPublisher,
            CostEstimator costEstimator,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = WebClient.builder()
                .baseUrl(geminiBaseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.geminiApiKey = geminiApiKey;
        this.parser = parser;
        this.rateLimiter = rateLimiter;
        this.kafkaPublisher = kafkaPublisher;
        this.costEstimator = costEstimator;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @PostMapping("/**")
    public ResponseEntity<?> proxy(@RequestBody byte[] body, HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String forwardPath = fullPath.replaceFirst("/llm-gateway", "");
        String model = parser.extractModel(forwardPath);
        RequestType requestType = parser.extractRequestType(forwardPath);
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");

        log.info("Proxy request: path={}, model={}, type={}", forwardPath, model, requestType);

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(PROVIDER_NAME);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, 0, false, "circuit_open", "Circuit breaker open for gemini");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", Map.of("message", "Gemini provider is currently unavailable", "code", 503)));
        }

        if (requestType == RequestType.CHAT_STREAM) {
            return handleStreamRequest(body, forwardPath, model, requestType, apiKey, breaker);
        }

        return handleStandardRequest(body, forwardPath, model, requestType, apiKey, breaker);
    }

    private ResponseEntity<?> handleStandardRequest(byte[] body, String forwardPath, String model,
                                                     RequestType requestType, ApiKey apiKey,
                                                     CircuitBreaker breaker) {
        long start = System.currentTimeMillis();
        try {
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

            publishEvent(apiKey, model, requestType, tokens, latency, false, "success", null);

            log.info("Proxy response: model={}, tokens={}, latency={}ms", model, tokens.totalTokens(), latency);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-LLM-Gateway-Provider", PROVIDER_NAME)
                    .body(responseBody);

        } catch (WebClientResponseException e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Gemini returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
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

    private ResponseEntity<?> handleStreamRequest(byte[] body, String forwardPath, String model,
                                                   RequestType requestType, ApiKey apiKey,
                                                   CircuitBreaker breaker) {
        long start = System.currentTimeMillis();

        Flux<String> sseStream = webClient.post()
                .uri(forwardPath + "?key=" + geminiApiKey + "&alt=sse")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunk -> {
                    TokenCounts tokens = parser.extractStreamTokenCounts(chunk);
                    if (tokens.totalTokens() > 0 && apiKey != null) {
                        rateLimiter.recordTokenUsage(apiKey.getId(), tokens.totalTokens());
                        long latency = System.currentTimeMillis() - start;
                        publishEvent(apiKey, model, requestType, tokens, latency, false, "success", null);
                    }
                })
                .doOnError(e -> {
                    long latency = System.currentTimeMillis() - start;
                    log.error("Stream proxy failed", e);
                    publishEvent(apiKey, model, requestType, TokenCounts.EMPTY, latency, false, "error", e.getMessage());
                })
                .map(chunk -> "data: " + chunk + "\n\n");

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-LLM-Gateway-Provider", PROVIDER_NAME);

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(sseStream);
    }

    private void publishEvent(ApiKey apiKey, String model, RequestType requestType,
                              TokenCounts tokens, long latency, boolean cacheHit,
                              String status, String errorMessage) {
        try {
            double cost = costEstimator.estimate(PROVIDER_NAME, model, tokens.inputTokens(), tokens.outputTokens());
            kafkaPublisher.publish(UsageEvent.builder()
                    .apiKeyId(apiKey != null ? apiKey.getId() : null)
                    .provider(PROVIDER_NAME)
                    .model(model)
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
