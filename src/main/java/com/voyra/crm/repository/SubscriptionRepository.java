package com.voyra.crm.repository;

import com.voyra.crm.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findByTenantId(String tenantId);
}
