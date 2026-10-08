package com.llmgateway.auth;

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
import java.util.Optional;

@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawKey = extractApiKey(request);

        if (rawKey == null) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing API key. Expected: Authorization: Bearer <key> or x-goog-api-key header");
            return;
        }

        Optional<ApiKey> apiKey = apiKeyService.validateKey(rawKey);

        if (apiKey.isEmpty()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or disabled API key");
            return;
        }

        request.setAttribute("apiKey", apiKey.get());
        filterChain.doFilter(request, response);
    }

    private String extractApiKey(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        String googApiKey = request.getHeader("x-goog-api-key");
        if (googApiKey != null && !googApiKey.isBlank()) {
            return googApiKey;
        }
        String queryKey = request.getParameter("key");
        if (queryKey != null && !queryKey.isBlank()) {
            return queryKey;
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/llm-gateway/");
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", Map.of(
                        "message", message,
                        "type", "authentication_error",
                        "code", status
                )
        ));
    }
}
