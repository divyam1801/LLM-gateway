package com.llmgateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "provider_config")
public class ProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_name", unique = true, nullable = false, length = 50)
    private String providerName;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "api_key_encrypted", length = 500)
    private String apiKeyEncrypted;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_mappings", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> modelMappings = new HashMap<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public Map<String, String> getModelMappings() { return modelMappings; }
    public void setModelMappings(Map<String, String> modelMappings) { this.modelMappings = modelMappings; }
}
