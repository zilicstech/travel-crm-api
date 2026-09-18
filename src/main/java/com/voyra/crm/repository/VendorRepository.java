package com.voyra.crm.repository;

import com.voyra.crm.entity.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, String> {

    List<Vendor> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    List<Vendor> findAllByOrderBySortOrderAscNameAsc();

    Optional<Vendor> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, String id);

    /**
     * Native because JPQL has no array containment operator. The @> form is what the GIN index
     * on service_types can actually serve - "type = ANY(service_types)" cannot use it.
     */
    @Query(value = """
            SELECT * FROM vendor
            WHERE is_active = TRUE
              AND service_types @> ARRAY[:type]::text[]
            ORDER BY sort_order, name
            """, nativeQuery = true)
    List<Vendor> findActiveByServiceType(@Param("type") String type);
}
