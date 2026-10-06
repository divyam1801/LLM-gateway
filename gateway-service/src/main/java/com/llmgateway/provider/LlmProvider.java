package com.llmgateway.provider;

import com.llmgateway.model.ChatRequest;
import com.llmgateway.model.ChatResponse;
import reactor.core.publisher.Flux;

public interface LlmProvider {

    String getProviderName();

    boolean supportsModel(String model);

    ChatResponse complete(ChatRequest request);

    Flux<String> stream(ChatRequest request);
}
