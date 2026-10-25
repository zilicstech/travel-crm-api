package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformSettingRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlatformSettingRequestRepository extends JpaRepository<PlatformSettingRequest, String> {

    /** Row lock so two admins deciding at once serialise; the second sees the first's decision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PlatformSettingRequest r WHERE r.id = :id")
    Optional<PlatformSettingRequest> findByIdForUpdate(@Param("id") String id);

    Optional<PlatformSettingRequest> findFirstBySettingKeyAndDecidedAtIsNull(String settingKey);

    List<PlatformSettingRequest> findByDecidedAtIsNullOrderByRequestedAtAsc();
}
