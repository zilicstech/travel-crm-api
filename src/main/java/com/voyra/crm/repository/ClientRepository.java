package com.voyra.crm.repository;

import com.voyra.crm.entity.Client;
import com.voyra.crm.enums.ClientType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, String> {

    Page<Client> findByType(ClientType type, Pageable pageable);

    /** Powers the Add Lead wizard's duplicate check. */
    Optional<Client> findByIdentifierAndIsActiveTrue(String identifier);

    boolean existsByIdentifierAndIsActiveTrue(String identifier);

    long countByIsActiveTrue();

    /** OR across two columns is unreadable as a derived name (blueprint §8.1). */
    @Query("""
            SELECT c FROM Client c
            WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.identifier) LIKE LOWER(CONCAT('%', :search, '%'))
            """)
    Page<Client> search(@Param("search") String search, Pageable pageable);
}
