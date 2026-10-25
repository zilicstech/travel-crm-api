package com.voyra.crm.repository;

import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TopupRequestRepository extends JpaRepository<TopupRequest, String> {

    /** Row lock so a double approval serialises; the second sees the first's decision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TopupRequest t WHERE t.id = :id")
    Optional<TopupRequest> findByIdForUpdate(@Param("id") String id);

    boolean existsByBankRefNormalized(String bankRefNormalized);

    boolean existsByWalletIdAndStatus(String walletId, TopupStatus status);

    List<TopupRequest> findByStatusAndModeAndCreatedAtBefore(TopupStatus status, TopupMode mode, Instant before);
}
