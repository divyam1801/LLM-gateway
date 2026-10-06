package com.llmgateway.controller;

import com.llmgateway.auth.ApiKeyService;
import com.llmgateway.auth.ApiKeyService.ApiKeyCreateResult;
import com.llmgateway.auth.ProviderConfigRepository;
import com.llmgateway.model.ApiKey;
import com.llmgateway.model.ProviderConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ApiKeyService apiKeyService;
    private final ProviderConfigRepository providerConfigRepository;

    public AdminController(ApiKeyService apiKeyService,
                           ProviderConfigRepository providerConfigRepository) {
        this.apiKeyService = apiKeyService;
        this.providerConfigRepository = providerConfigRepository;
    }

    // --- API Key Management ---

    @PostMapping("/keys")
    public ResponseEntity<Map<String, Object>> createKey(@Valid @RequestBody CreateKeyRequest request) {
        ApiKeyCreateResult result = apiKeyService.createKey(request.name(), request.owner());
        ApiKey key = result.apiKey();

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", key.getId(),
                "key", result.rawKey(),
                "name", key.getName(),
                "owner", key.getOwner(),
                "message", "Save this key — it will not be shown again."
        ));
    }

    @GetMapping("/keys")
    public List<ApiKeyResponse> listKeys() {
        return apiKeyService.listKeys().stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    @GetMapping("/keys/{id}")
    public ResponseEntity<ApiKeyResponse> getKey(@PathVariable UUID id) {
        return apiKeyService.getKey(id)
                .map(key -> ResponseEntity.ok(ApiKeyResponse.from(key)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/keys/{id}")
    public ResponseEntity<ApiKeyResponse> updateKey(@PathVariable UUID id,
                                                    @RequestBody UpdateKeyRequest request) {
        return apiKeyService.updateKey(id, request.name(), request.rateLimitRpm(),
                        request.rateLimitTpm(), request.enabled())
                .map(key -> ResponseEntity.ok(ApiKeyResponse.from(key)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Void> revokeKey(@PathVariable UUID id) {
        if (apiKeyService.revokeKey(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    // --- Provider Management ---

    @GetMapping("/providers")
    public List<ProviderConfig> listProviders() {
        return providerConfigRepository.findAll();
    }

    @PutMapping("/providers/{id}/toggle")
    public ResponseEntity<ProviderConfig> toggleProvider(@PathVariable UUID id) {
        return providerConfigRepository.findById(id)
                .map(config -> {
                    config.setEnabled(!config.isEnabled());
                    return ResponseEntity.ok(providerConfigRepository.save(config));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/providers/{id}/models")
    public ResponseEntity<ProviderConfig> updateModelMappings(
            @PathVariable UUID id,
            @RequestBody Map<String, String> mappings) {
        return providerConfigRepository.findById(id)
                .map(config -> {
                    config.setModelMappings(mappings);
                    return ResponseEntity.ok(providerConfigRepository.save(config));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- DTOs ---

    record CreateKeyRequest(@NotBlank String name, @NotBlank String owner) {}

    record UpdateKeyRequest(String name, Integer rateLimitRpm,
                            Integer rateLimitTpm, Boolean enabled) {}

    record ApiKeyResponse(UUID id, String name, String owner, int rateLimitRpm,
                          int rateLimitTpm, boolean enabled, String createdAt,
                          String lastUsedAt) {
        static ApiKeyResponse from(ApiKey key) {
            return new ApiKeyResponse(
                    key.getId(), key.getName(), key.getOwner(),
                    key.getRateLimitRpm(), key.getRateLimitTpm(), key.isEnabled(),
                    key.getCreatedAt().toString(),
                    key.getLastUsedAt() != null ? key.getLastUsedAt().toString() : null
            );
        }
    }
}
