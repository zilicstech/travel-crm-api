package com.voyra.crm.repository;

import com.voyra.crm.entity.SubscriptionInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, String> {

    List<SubscriptionInvoice> findByTenantIdAndStatusNotOrderByCreatedAtDesc(String tenantId, String status);
}
