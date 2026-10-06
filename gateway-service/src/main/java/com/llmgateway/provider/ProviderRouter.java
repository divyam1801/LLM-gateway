package com.llmgateway.provider;

import com.llmgateway.auth.ProviderConfigRepository;
import com.llmgateway.config.ResilienceConfig.ClientErrorException;
import com.llmgateway.model.ChatRequest;
import com.llmgateway.model.ChatResponse;
import com.llmgateway.model.ProviderConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final Map<String, LlmProvider> providerMap;
    private final ProviderConfigRepository configRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ProviderRouter(List<LlmProvider> providers,
                          ProviderConfigRepository configRepository,
                          CircuitBreakerRegistry circuitBreakerRegistry) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(LlmProvider::getProviderName, p -> p));
        this.configRepository = configRepository;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public RoutingResult complete(ChatRequest request) {
        LlmProvider primary = resolveProvider(request.model());
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(primary.getProviderName());

        try {
            ChatResponse response = CircuitBreaker.decorateSupplier(breaker,
                    () -> primary.complete(request)).get();
            return new RoutingResult(response, primary.getProviderName(), false, null);
        } catch (Exception e) {
            if (isClientError(e)) throw e;
            log.warn("Provider {} failed for model {}, attempting failover",
                    primary.getProviderName(), request.model(), e);
        }

        return attemptFailover(request, primary.getProviderName(), false);
    }

    public StreamRoutingResult stream(ChatRequest request) {
        LlmProvider primary = resolveProvider(request.model());
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(primary.getProviderName());

        if (breaker.getState() != CircuitBreaker.State.OPEN) {
            Flux<String> flux = primary.stream(request);
            return new StreamRoutingResult(flux, primary.getProviderName(), false, null);
        }

        log.warn("Circuit breaker OPEN for {}, failing over", primary.getProviderName());
        RoutingResult fallback = attemptFailover(request, primary.getProviderName(), true);

        LlmProvider fallbackProvider = providerMap.get(fallback.provider());
        if (fallbackProvider != null) {
            Flux<String> flux = fallbackProvider.stream(request.withModel(
                    getFallbackModel(primary.getProviderName(), request.model())));
            return new StreamRoutingResult(flux, fallbackProvider.getProviderName(),
                    true, primary.getProviderName());
        }

        throw new AllProvidersDownException("All providers are unavailable");
    }

    public LlmProvider resolveProvider(String model) {
        return providerMap.values().stream()
                .filter(p -> p.supportsModel(model))
                .findFirst()
                .orElseThrow(() -> new UnsupportedModelException(
                        "No provider found for model: " + model));
    }

    public Map<String, CircuitBreaker.State> getCircuitBreakerStates() {
        return providerMap.keySet().stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> circuitBreakerRegistry.circuitBreaker(name).getState()
                ));
    }

    public CircuitBreaker.State getCircuitBreakerState(String providerName) {
        return circuitBreakerRegistry.circuitBreaker(providerName).getState();
    }

    private RoutingResult attemptFailover(ChatRequest request, String failedProvider,
                                          boolean streamMode) {
        List<ProviderConfig> configs = configRepository.findByEnabledTrueOrderByPriorityAsc();

        for (ProviderConfig config : configs) {
            if (config.getProviderName().equals(failedProvider)) continue;

            LlmProvider fallback = providerMap.get(config.getProviderName());
            if (fallback == null) continue;

            CircuitBreaker fallbackBreaker = circuitBreakerRegistry
                    .circuitBreaker(config.getProviderName());

            if (fallbackBreaker.getState() == CircuitBreaker.State.OPEN) continue;

            String fallbackModel = getFallbackModel(failedProvider, request.model());
            if (fallbackModel == null) continue;

            ChatRequest fallbackRequest = request.withModel(fallbackModel);

            try {
                log.info("Failing over to {} with model {}", config.getProviderName(), fallbackModel);
                ChatResponse response = CircuitBreaker.decorateSupplier(fallbackBreaker,
                        () -> fallback.complete(fallbackRequest)).get();
                return new RoutingResult(response, config.getProviderName(),
                        true, failedProvider);
            } catch (Exception e) {
                log.warn("Fallback provider {} also failed", config.getProviderName(), e);
            }
        }

        throw new AllProvidersDownException("All providers are unavailable");
    }

    private String getFallbackModel(String fromProvider, String originalModel) {
        return configRepository.findByProviderName(fromProvider)
                .map(config -> config.getModelMappings().get(originalModel))
                .orElse(null);
    }

    private boolean isClientError(Exception e) {
        if (e instanceof WebClientResponseException wce) {
            return wce.getStatusCode().is4xxClientError();
        }
        return e instanceof ClientErrorException;
    }

    public record RoutingResult(ChatResponse response, String provider,
                                boolean fallbackUsed, String originalProvider) {}

    public record StreamRoutingResult(Flux<String> flux, String provider,
                                      boolean fallbackUsed, String originalProvider) {}

    public static class UnsupportedModelException extends RuntimeException {
        public UnsupportedModelException(String message) {
            super(message);
        }
    }

    public static class AllProvidersDownException extends RuntimeException {
        public AllProvidersDownException(String message) {
            super(message);
        }
    }
}
