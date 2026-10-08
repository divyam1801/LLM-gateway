package com.llmgateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.model.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        if (apiKey == null) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimiter.RateLimitResult result = rateLimiter.checkRequestLimit(
                apiKey.getId(), apiKey.getRateLimitRpm());

        RateLimiter.RateLimitInfo info = rateLimiter.getRateLimitInfo(
                apiKey.getId(), apiKey.getRateLimitRpm(), apiKey.getRateLimitTpm());

        setRateLimitHeaders(response, info);

        if (!result.allowed()) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(result.resetInSeconds()));
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "error", Map.of(
                            "message", "Rate limit exceeded. Try again in " + result.resetInSeconds() + "s",
                            "type", "rate_limit_error",
                            "code", 429
                    )
            ));
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/llm-gateway/");
    }

    private void setRateLimitHeaders(HttpServletResponse response, RateLimiter.RateLimitInfo info) {
        response.setHeader("X-RateLimit-Limit-Requests", String.valueOf(info.limitRequests()));
        response.setHeader("X-RateLimit-Remaining-Requests", String.valueOf(info.remainingRequests()));
        response.setHeader("X-RateLimit-Limit-Tokens", String.valueOf(info.limitTokens()));
        response.setHeader("X-RateLimit-Remaining-Tokens", String.valueOf(info.remainingTokens()));
        response.setHeader("X-RateLimit-Reset-Requests", info.resetRequestsInSeconds() + "s");
        response.setHeader("X-RateLimit-Reset-Tokens", info.resetTokensInSeconds() + "s");
    }
}
