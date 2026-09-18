package com.voyra.crm.repository;

import com.voyra.crm.entity.Client;
import com.voyra.crm.enums.ClientType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /**
     * Catches a B2C phone written differently ("+91 98765 43210" vs "9876543210") by comparing
     * only the last 10 digits of each side, both stripped of everything but digits. B2B
     * identifiers are handles, not phones, so this is scoped to B2C.
     */
    @Query(value = """
            SELECT * FROM client
            WHERE is_active = TRUE
              AND type = 'B2C'
              AND length(regexp_replace(:phoneDigits, '[^0-9]', '', 'g')) >= 10
              AND RIGHT(regexp_replace(identifier, '[^0-9]', '', 'g'), 10)
                = RIGHT(regexp_replace(:phoneDigits, '[^0-9]', '', 'g'), 10)
            """, nativeQuery = true)
    List<Client> findByPhoneSuffix(@Param("phoneDigits") String phoneDigits);

    /** pg_trgm similarity, not exact match - catches "Ajay Sharma" vs "Ajay Sharme". */
    @Query(value = """
            SELECT * FROM client
            WHERE is_active = TRUE
              AND similarity(name, :name) > 0.4
            ORDER BY similarity(name, :name) DESC
            LIMIT 5
            """, nativeQuery = true)
    List<Client> findSimilarByName(@Param("name") String name);
}
