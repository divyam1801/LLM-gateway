package com.llmgateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(Exception.class)
                .ignoreExceptions(ClientErrorException.class)
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker openaiCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("openai");
    }

    @Bean
    public CircuitBreaker ollamaCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("ollama");
    }

    public static class ClientErrorException extends RuntimeException {
        public ClientErrorException(String message) {
            super(message);
        }
    }
}
