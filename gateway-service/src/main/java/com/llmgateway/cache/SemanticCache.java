package com.llmgateway.cache;

import java.util.Optional;

public interface SemanticCache {

    record LookupResult(Optional<byte[]> response, float[] embedding, double similarity) {
        public static final LookupResult EMPTY = new LookupResult(Optional.empty(), null, -1);
    }

    LookupResult lookup(String promptText, String model);

    void store(String promptText, String model, byte[] response, float[] embedding);

    void flush();

    long size();
}
