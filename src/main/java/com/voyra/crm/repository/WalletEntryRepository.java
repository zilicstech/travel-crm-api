package com.voyra.crm.repository;

import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.WalletEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface WalletEntryRepository extends JpaRepository<WalletEntry, String> {

    Optional<WalletEntry> findByIdempotencyKey(String idempotencyKey);

    List<WalletEntry> findByWalletIdOrderByCreatedAtDescIdDesc(String walletId, Pageable pageable);

    Page<WalletEntry> findPagedByWalletIdOrderByCreatedAtDescIdDesc(String walletId, Pageable pageable);

    boolean existsByRelatedEntryIdAndType(String relatedEntryId, WalletEntryType type);

    Optional<WalletEntry> findFirstByRelatedEntryIdAndType(String relatedEntryId, WalletEntryType type);

    /** HOLD rows no HOLD_RELEASE points at. */
    @Query("""
            SELECT h FROM WalletEntry h WHERE h.walletId = :walletId
              AND h.type = com.voyra.crm.enums.WalletEntryType.HOLD
              AND NOT EXISTS (SELECT 1 FROM WalletEntry r
                              WHERE r.type = com.voyra.crm.enums.WalletEntryType.HOLD_RELEASE
                                AND r.relatedEntryId = h.id)
            """)
    List<WalletEntry> findOpenHolds(@Param("walletId") String walletId);

    /** Signed sum of one entry type posted against a reference (e.g. ADJUSTMENTs against a top-up: zero or negative). */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM WalletEntry e WHERE e.type = :type AND e.refType = :refType AND e.refId = :refId")
    BigDecimal sumAmountByTypeAndRef(@Param("type") WalletEntryType type, @Param("refType") String refType, @Param("refId") String refId);
}
