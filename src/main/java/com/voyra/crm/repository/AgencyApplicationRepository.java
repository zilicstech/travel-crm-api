package com.voyra.crm.repository;

import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.enums.ApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgencyApplicationRepository extends JpaRepository<AgencyApplication, String> {

    /** Row lock for review actions, so an approve that is still migrating the schema cannot be overwritten by a decision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AgencyApplication a where a.id = :id")
    Optional<AgencyApplication> findByIdForUpdate(@Param("id") String id);

    Page<AgencyApplication> findByStatusInOrderBySubmittedAtDesc(Collection<ApplicationStatus> statuses, Pageable pageable);

    Optional<AgencyApplication> findFirstByOwnerEmailIgnoreCaseAndStatusIn(String ownerEmail, Collection<ApplicationStatus> statuses);

    List<AgencyApplication> findByStatusAndUpdatedAtBefore(ApplicationStatus status, Instant before);
}
