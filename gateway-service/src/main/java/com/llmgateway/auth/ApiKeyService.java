package com.llmgateway.auth;

import com.llmgateway.model.ApiKey;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ApiKeyCreateResult createKey(String name, String owner) {
        String rawKey = generateRawKey();
        String hash = passwordEncoder.encode(rawKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setName(name);
        apiKey.setOwner(owner);
        apiKey.setKeyHash(hash);

        ApiKey saved = repository.save(apiKey);
        return new ApiKeyCreateResult(saved, rawKey);
    }

    public Optional<ApiKey> validateKey(String rawKey) {
        List<ApiKey> allKeys = repository.findAll();
        for (ApiKey key : allKeys) {
            if (key.isEnabled() && passwordEncoder.matches(rawKey, key.getKeyHash())) {
                key.setLastUsedAt(LocalDateTime.now());
                repository.save(key);
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    public List<ApiKey> listKeys() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<ApiKey> getKey(UUID id) {
        return repository.findById(id);
    }

    @Transactional
    public Optional<ApiKey> updateKey(UUID id, String name, Integer rateLimitRpm,
                                      Integer rateLimitTpm, Boolean enabled) {
        return repository.findById(id).map(key -> {
            if (name != null) key.setName(name);
            if (rateLimitRpm != null) key.setRateLimitRpm(rateLimitRpm);
            if (rateLimitTpm != null) key.setRateLimitTpm(rateLimitTpm);
            if (enabled != null) key.setEnabled(enabled);
            return repository.save(key);
        });
    }

    @Transactional
    public boolean revokeKey(UUID id) {
        return repository.findById(id).map(key -> {
            key.setEnabled(false);
            repository.save(key);
            return true;
        }).orElse(false);
    }

    private String generateRawKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "gw-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ApiKeyCreateResult(ApiKey apiKey, String rawKey) {}
}
