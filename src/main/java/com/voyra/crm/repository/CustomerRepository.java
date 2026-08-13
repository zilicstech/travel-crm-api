package com.voyra.crm.repository;

import com.voyra.crm.entity.Customer;
import com.voyra.crm.enums.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {

    List<Customer> findByAgentId(String agentId);

    Page<Customer> findByAgentId(String agentId, Pageable pageable);

    List<Customer> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCaseOrPhoneContaining(
            String name, String email, String phone);

    /** Powers the Add Lead wizard's "customer auto-lookup by phone" feature. */
    List<Customer> findByPhoneEndingWith(String phoneSuffix);

    long countByStatus(CustomerStatus status);

    /** Mixed AND/OR (agent scope AND name-or-email-or-phone) is unreadable as a derived name (blueprint §8.1). */
    @Query("""
            SELECT c FROM Customer c
            WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%'))
            """)
    Page<Customer> search(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT c FROM Customer c
            WHERE c.agentId = :agentId
              AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Customer> searchByAgentId(@Param("agentId") String agentId, @Param("search") String search, Pageable pageable);

    /** Keeps the denormalized agent_name snapshot live-synced on Agent rename. */
    @Modifying
    @Query("UPDATE Customer c SET c.agentName = :name WHERE c.agentId = :agentId")
    void updateAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
