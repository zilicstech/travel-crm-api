package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformAdmin;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, String> {

    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<PlatformAdmin> findByIsActiveTrue();

    /** Locks every active admin, so two deactivations at once serialise and cannot leave nobody active. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlatformAdmin p WHERE p.isActive = true")
    List<PlatformAdmin> findActiveForUpdate();
}
