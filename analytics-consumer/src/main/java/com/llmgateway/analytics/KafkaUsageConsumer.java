package com.llmgateway.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class KafkaUsageConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUsageConsumer.class);

    private final UsageRepository repository;

    public KafkaUsageConsumer(UsageRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "llm-usage-events", groupId = "analytics-consumer-group")
    public void consume(Map<String, Object> event) {
        try {
            UsageEventDocument doc = new UsageEventDocument();
            doc.setApiKeyId(java.util.UUID.fromString((String) event.get("apiKeyId")));
            doc.setProvider((String) event.get("provider"));
            doc.setModel((String) event.get("model"));
            doc.setInputTokens(toInt(event.get("inputTokens")));
            doc.setOutputTokens(toInt(event.get("outputTokens")));
            doc.setEstimatedCostUsd(toDouble(event.get("estimatedCostUsd")));
            doc.setLatencyMs(toLong(event.get("latencyMs")));
            doc.setCacheHit(toBool(event.get("cacheHit")));
            doc.setFallbackUsed(toBool(event.get("fallbackUsed")));
            doc.setStatus((String) event.get("status"));
            doc.setErrorMessage((String) event.get("errorMessage"));
            doc.setTimestamp(java.time.Instant.parse((String) event.get("timestamp")));

            repository.save(doc);
            log.debug("Saved usage event: provider={}, model={}", doc.getProvider(), doc.getModel());
        } catch (Exception e) {
            log.error("Failed to process usage event: {}", event, e);
        }
    }

    private int toInt(Object val) {
        return val instanceof Number n ? n.intValue() : 0;
    }

    private long toLong(Object val) {
        return val instanceof Number n ? n.longValue() : 0L;
    }

    private double toDouble(Object val) {
        return val instanceof Number n ? n.doubleValue() : 0.0;
    }

    private boolean toBool(Object val) {
        return val instanceof Boolean b && b;
    }
}
