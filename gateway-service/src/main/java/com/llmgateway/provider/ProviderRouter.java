package com.llmgateway.provider;

import com.llmgateway.model.ChatRequest;
import com.llmgateway.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final List<LlmProvider> providers;

    public ProviderRouter(List<LlmProvider> providers) {
        this.providers = providers;
    }

    public LlmProvider resolveProvider(String model) {
        return providers.stream()
                .filter(p -> p.supportsModel(model))
                .findFirst()
                .orElseThrow(() -> new UnsupportedModelException(
                        "No provider found for model: " + model));
    }

    public Optional<LlmProvider> findProviderByName(String name) {
        return providers.stream()
                .filter(p -> p.getProviderName().equals(name))
                .findFirst();
    }

    public ChatResponse complete(ChatRequest request) {
        LlmProvider provider = resolveProvider(request.model());
        log.info("Routing completion to {} for model {}", provider.getProviderName(), request.model());
        return provider.complete(request);
    }

    public Flux<String> stream(ChatRequest request) {
        LlmProvider provider = resolveProvider(request.model());
        log.info("Routing stream to {} for model {}", provider.getProviderName(), request.model());
        return provider.stream(request);
    }

    public String getProviderNameForModel(String model) {
        return resolveProvider(model).getProviderName();
    }

    public static class UnsupportedModelException extends RuntimeException {
        public UnsupportedModelException(String message) {
            super(message);
        }
    }
}
