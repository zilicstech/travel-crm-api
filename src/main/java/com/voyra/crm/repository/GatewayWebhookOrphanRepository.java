package com.voyra.crm.repository;

import com.voyra.crm.entity.GatewayWebhookOrphan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GatewayWebhookOrphanRepository extends JpaRepository<GatewayWebhookOrphan, String> {

    Optional<GatewayWebhookOrphan> findByGatewayOrderIdAndEvent(String gatewayOrderId, String event);
}
