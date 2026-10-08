package com.llmgateway.cache;

import java.util.Optional;

public interface SemanticCache {

    Optional<byte[]> lookup(String promptText, String model);

    void store(String promptText, String model, byte[] response);

    void flush();

    long size();
}
