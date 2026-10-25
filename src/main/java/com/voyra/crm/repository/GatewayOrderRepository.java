package com.voyra.crm.repository;

import com.voyra.crm.entity.GatewayOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GatewayOrderRepository extends JpaRepository<GatewayOrder, String> {

    Optional<GatewayOrder> findByGatewayOrderId(String gatewayOrderId);
}
