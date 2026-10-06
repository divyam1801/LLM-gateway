package com.llmgateway.cache;

import com.llmgateway.model.ChatResponse;

import java.util.Optional;

public interface SemanticCache {

    Optional<ChatResponse> lookup(String promptText, String model);

    void store(String promptText, String model, ChatResponse response);

    void flush();

    long size();
}
