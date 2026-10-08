package com.llmgateway.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "gateway.pricing")
public class CostEstimator {

    private Map<String, Map<String, ModelPricing>> providers = new HashMap<>();

    public void setGemini(Map<String, ModelPricing> gemini) { providers.put("gemini", gemini); }

    public double estimate(String provider, String model, int inputTokens, int outputTokens) {
        Map<String, ModelPricing> models = providers.get(provider);
        if (models == null) return 0.0;

        ModelPricing pricing = models.get(model);
        if (pricing == null) return 0.0;

        return (inputTokens * pricing.getInputPerMillion() / 1_000_000.0)
                + (outputTokens * pricing.getOutputPerMillion() / 1_000_000.0);
    }

    public static class ModelPricing {
        private double inputPerMillion;
        private double outputPerMillion;

        public double getInputPerMillion() { return inputPerMillion; }
        public void setInputPerMillion(double inputPerMillion) { this.inputPerMillion = inputPerMillion; }
        public double getOutputPerMillion() { return outputPerMillion; }
        public void setOutputPerMillion(double outputPerMillion) { this.outputPerMillion = outputPerMillion; }
    }
}
