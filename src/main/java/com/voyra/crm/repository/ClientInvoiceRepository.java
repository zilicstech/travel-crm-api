package com.voyra.crm.repository;

import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ClientInvoiceRepository extends JpaRepository<ClientInvoice, String> {

    List<ClientInvoice> findByAgentId(String agentId);

    Page<ClientInvoice> findByAgentId(String agentId, Pageable pageable);

    List<ClientInvoice> findByCustomerId(String customerId);

    @Query("SELECT COALESCE(SUM(c.totalWithGst - c.amountPaid), 0) FROM ClientInvoice c WHERE c.status <> :paid")
    BigDecimal sumOutstanding(@Param("paid") InvoiceStatus paid);

    @Modifying
    @Query("UPDATE ClientInvoice c SET c.customerName = :name WHERE c.customerId = :customerId")
    void updateCustomerNameForCustomer(@Param("customerId") String customerId, @Param("name") String name);
}
