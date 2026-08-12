package com.voyra.crm.repository;

import com.voyra.crm.entity.ClientInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientInvoiceRepository extends JpaRepository<ClientInvoice, String> {

    List<ClientInvoice> findByAgentId(String agentId);

    List<ClientInvoice> findByCustomerId(String customerId);

    @Modifying
    @Query("UPDATE ClientInvoice c SET c.customerName = :name WHERE c.customerId = :customerId")
    void updateCustomerNameForCustomer(@Param("customerId") String customerId, @Param("name") String name);
}
