package com.llmgateway.analytics;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageRepository extends MongoRepository<UsageEventDocument, String> {

    List<UsageEventDocument> findByTimestampAfterOrderByTimestampDesc(Instant after);

    List<UsageEventDocument> findByApiKeyIdAndTimestampAfterOrderByTimestampDesc(UUID apiKeyId, Instant after);

    List<UsageEventDocument> findByProviderAndTimestampAfterOrderByTimestampDesc(String provider, Instant after);

    long countByTimestampAfter(Instant after);

    long countByCacheHitTrueAndTimestampAfter(Instant after);
}
