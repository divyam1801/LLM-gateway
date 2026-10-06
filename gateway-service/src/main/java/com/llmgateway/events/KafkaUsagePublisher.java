package com.llmgateway.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaUsagePublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaUsagePublisher.class);

    private final KafkaTemplate<String, UsageEvent> kafkaTemplate;
    private final String topic;

    public KafkaUsagePublisher(KafkaTemplate<String, UsageEvent> kafkaTemplate,
                               @Value("${gateway.kafka.usage-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(UsageEvent event) {
        kafkaTemplate.send(topic, event.apiKeyId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish usage event", ex);
                    } else {
                        log.debug("Published usage event for key={}, provider={}",
                                event.apiKeyId(), event.provider());
                    }
                });
    }
}
