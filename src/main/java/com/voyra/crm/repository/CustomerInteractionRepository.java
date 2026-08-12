package com.voyra.crm.repository;

import com.voyra.crm.entity.CustomerInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerInteractionRepository extends JpaRepository<CustomerInteraction, String> {

    List<CustomerInteraction> findByCustomerIdOrderByCreatedDateDesc(String customerId);

    @Modifying
    @Query("UPDATE CustomerInteraction i SET i.authorName = :name WHERE i.authorAgentId = :agentId")
    void updateAuthorNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
