package com.llmgateway.auth;

import com.llmgateway.model.ProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, UUID> {

    Optional<ProviderConfig> findByProviderName(String providerName);

    List<ProviderConfig> findByEnabledTrueOrderByPriorityAsc();
}
