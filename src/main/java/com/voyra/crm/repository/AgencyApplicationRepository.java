package com.voyra.crm.repository;

import com.voyra.crm.entity.AgencyApplication;
import com.voyra.crm.enums.ApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgencyApplicationRepository extends JpaRepository<AgencyApplication, String> {

    Page<AgencyApplication> findByStatusInOrderBySubmittedAtDesc(Collection<ApplicationStatus> statuses, Pageable pageable);

    Optional<AgencyApplication> findFirstByOwnerEmailIgnoreCaseAndStatusIn(String ownerEmail, Collection<ApplicationStatus> statuses);

    List<AgencyApplication> findByStatusAndUpdatedAtBefore(ApplicationStatus status, Instant before);
}
