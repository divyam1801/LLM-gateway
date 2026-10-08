package com.llmgateway.analytics;

import com.llmgateway.events.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class UsageEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UsageEventConsumer.class);

    private final UsageRepository usageRepository;

    public UsageEventConsumer(UsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    @KafkaListener(topics = "${gateway.kafka.usage-topic}", groupId = "gateway-analytics")
    public void consume(UsageEvent event) {
        UsageEventDocument doc = new UsageEventDocument();
        doc.setApiKeyId(event.apiKeyId());
        doc.setProvider(event.provider());
        doc.setModel(event.model());
        doc.setRequestType(event.requestType());
        doc.setInputTokens(event.inputTokens());
        doc.setOutputTokens(event.outputTokens());
        doc.setEstimatedCostUsd(event.estimatedCostUsd());
        doc.setLatencyMs(event.latencyMs());
        doc.setCacheHit(event.cacheHit());
        doc.setFallbackUsed(event.fallbackUsed());
        doc.setStatus(event.status());
        doc.setErrorMessage(event.errorMessage());
        doc.setTimestamp(event.timestamp());

        usageRepository.save(doc);
        log.debug("Saved usage event: provider={}, model={}, status={}", event.provider(), event.model(), event.status());
    }
}
