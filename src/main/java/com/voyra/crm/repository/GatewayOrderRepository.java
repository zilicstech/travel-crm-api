package com.voyra.crm.repository;

import com.voyra.crm.entity.GatewayOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.voyra.crm.enums.GatewayOrderPurpose;
import com.voyra.crm.enums.GatewayOrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GatewayOrderRepository extends JpaRepository<GatewayOrder, String> {

    Optional<GatewayOrder> findByGatewayOrderId(String gatewayOrderId);

    List<GatewayOrder> findByPurposeAndStatusAndCreatedAtBefore(GatewayOrderPurpose purpose, GatewayOrderStatus status, Instant before);

    /** Row lock so concurrent webhook deliveries for one order run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GatewayOrder g WHERE g.gatewayOrderId = :gatewayOrderId")
    Optional<GatewayOrder> findByGatewayOrderIdForUpdate(@Param("gatewayOrderId") String gatewayOrderId);
}
