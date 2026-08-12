package com.voyra.crm.repository;

import com.voyra.crm.entity.Visa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VisaRepository extends JpaRepository<Visa, String> {

    List<Visa> findByAgentId(String agentId);

    List<Visa> findByCustomerId(String customerId);

    @Modifying
    @Query("UPDATE Visa v SET v.agentName = :name WHERE v.agentId = :agentId")
    void updateAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);

    @Modifying
    @Query("UPDATE Visa v SET v.customerName = :name WHERE v.customerId = :customerId")
    void updateCustomerNameForCustomer(@Param("customerId") String customerId, @Param("name") String name);
}
