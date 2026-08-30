package com.voyra.crm.repository;

import com.voyra.crm.entity.Client;
import com.voyra.crm.enums.ClientType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, String> {

    List<Client> findByAgentId(String agentId);

    Page<Client> findByAgentId(String agentId, Pageable pageable);

    Page<Client> findByType(ClientType type, Pageable pageable);

    Page<Client> findByAgentIdAndType(String agentId, ClientType type, Pageable pageable);

    /**
     * Powers the Add Lead wizard's duplicate check. Deliberately searches the whole tenant
     * rather than the caller's own clients - an agent must be able to discover that a walk-in
     * already exists under a colleague, or the agency accumulates duplicate accounts.
     */
    Optional<Client> findByIdentifierAndIsActiveTrue(String identifier);

    boolean existsByIdentifierAndIsActiveTrue(String identifier);

    long countByIsActiveTrue();

    /** Mixed AND/OR (agent scope AND name-or-identifier) is unreadable as a derived name (blueprint §8.1). */
    @Query("""
            SELECT c FROM Client c
            WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.identifier) LIKE LOWER(CONCAT('%', :search, '%'))
            """)
    Page<Client> search(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT c FROM Client c
            WHERE c.agentId = :agentId
              AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.identifier) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Client> searchByAgentId(@Param("agentId") String agentId, @Param("search") String search, Pageable pageable);

    /** Keeps the denormalized agent_name snapshot live-synced on Agent rename. */
    @Modifying
    @Query("UPDATE Client c SET c.agentName = :name WHERE c.agentId = :agentId")
    void updateAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
