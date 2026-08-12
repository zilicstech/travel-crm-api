package com.voyra.crm.repository;

import com.voyra.crm.entity.Customer;
import com.voyra.crm.enums.CustomerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {

    List<Customer> findByAgentId(String agentId);

    List<Customer> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCaseOrPhoneContaining(
            String name, String email, String phone);

    /** Powers the Add Lead wizard's "customer auto-lookup by phone" feature. */
    List<Customer> findByPhoneEndingWith(String phoneSuffix);

    long countByStatus(CustomerStatus status);

    /** Keeps the denormalized agent_name snapshot live-synced on Agent rename. */
    @Modifying
    @Query("UPDATE Customer c SET c.agentName = :name WHERE c.agentId = :agentId")
    void updateAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
