package com.voyra.crm.repository;

import com.voyra.crm.entity.Inquiry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, String> {

    Page<Inquiry> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** @param pattern a lower-case LIKE pattern, e.g. {@code %acme%} */
    @Query("SELECT i FROM Inquiry i WHERE LOWER(i.name) LIKE :pattern OR LOWER(i.email) LIKE :pattern "
            + "OR LOWER(i.phone) LIKE :pattern OR LOWER(i.company) LIKE :pattern "
            + "OR LOWER(i.message) LIKE :pattern OR LOWER(i.source) LIKE :pattern ORDER BY i.createdAt DESC")
    Page<Inquiry> search(@Param("pattern") String pattern, Pageable pageable);
}
