package com.llmgateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.model.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@Component
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
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing or invalid Authorization header. Expected: Bearer <api-key>");
            return;
        }

        String rawKey = authHeader.substring(7);
        Optional<ApiKey> apiKey = apiKeyService.validateKey(rawKey);

        if (apiKey.isEmpty()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or disabled API key");
            return;
        }

        request.setAttribute("apiKey", apiKey.get());
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/");
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
