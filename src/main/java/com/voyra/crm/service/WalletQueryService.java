package com.voyra.crm.service;

import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.ReceivableResponse;
import com.voyra.crm.dto.WalletAdjustRequest;
import com.voyra.crm.dto.WalletEntryResponse;
import com.voyra.crm.dto.WalletSummaryResponse;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.ReceivableStatus;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.PlatformReceivableRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Wallet reads, the statement CSV, and the plain-field wallet writes (threshold, test flag) plus the audited platform
 * actions. Money moves only through {@link WalletService}; nothing here does balance arithmetic beyond available.
 */
@Service
@RequiredArgsConstructor
public class WalletQueryService {

    private final WalletRepository walletRepository;
    private final WalletEntryRepository entryRepository;
    private final PlatformReceivableRepository receivableRepository;
    private final TopupRequestRepository topupRequestRepository;
    private final WalletService walletService;
    private final PlatformAuditService auditService;

    @Transactional(readOnly = true)
    public WalletSummaryResponse summary(String tenantId) {
        Wallet w = find(tenantId);
        List<ReceivableResponse> due = receivableRepository
                .findByTenantIdAndStatusOrderByCreatedAt(tenantId, ReceivableStatus.DUE).stream()
                .map(r -> ReceivableResponse.builder().id(r.getId()).amount(r.getAmount())
                        .originalAmount(r.getOriginalAmount()).reason(r.getReason().name()).createdAt(r.getCreatedAt()).build())
                .toList();
        return WalletSummaryResponse.builder()
                .balance(w.getBalance()).held(w.getHeld()).available(w.getBalance().subtract(w.getHeld()))
                .currency(w.getCurrency()).status(w.getStatus().name()).isTest(w.getIsTest())
                .lowBalanceThreshold(w.getLowBalanceThreshold()).dueReceivables(due)
                .pendingTopups((int) topupRequestRepository.countByWalletIdAndStatus(w.getId(), TopupStatus.PENDING))
                .build();
    }

    @Transactional(readOnly = true)
    public PagedResponse<WalletEntryResponse> entries(String tenantId, Pageable pageable) {
        Wallet w = find(tenantId);
        return PagedResponse.from(entryRepository.findPagedByWalletIdOrderByCreatedAtDescIdDesc(w.getId(), pageable),
                WalletQueryService::toResponse);
    }

    @Transactional(readOnly = true)
    public String csv(String tenantId) {
        Wallet w = find(tenantId);
        List<List<String>> rows = entryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(w.getId(), Pageable.unpaged()).stream()
                .map(WalletQueryService::toResponse)
                .map(e -> List.of(String.valueOf(e.getCreatedAt()), e.getType(), e.getAmount().toPlainString(),
                        e.getBalanceAfter().toPlainString(), e.getHeldAfter().toPlainString(),
                        e.getAvailableAfter().toPlainString(), safe(e.getNarration()), safe(e.getCreatedByName())))
                .toList();
        return CsvWriter.write(List.of("Date", "Type", "Amount", "Balance", "Held", "Available", "Narration", "By"), rows);
    }

    /** Row lock so the update cannot race a posting that rewrites the same wallet row. */
    @Transactional
    public void setThreshold(String tenantId, BigDecimal threshold) {
        Wallet w = lock(tenantId);
        w.setLowBalanceThreshold(threshold);
        w.setLowBalanceNotifiedAt(null); // re-arm: the next posting decides afresh
        walletRepository.save(w);
    }

    @Transactional
    public void setTest(String tenantId, boolean isTest) {
        Wallet w = lock(tenantId);
        w.setIsTest(isTest);
        walletRepository.save(w);
        auditService.record(PlatformAuditAction.WALLET_TEST_FLAG, "Wallet", w.getId(), Map.of("tenantId", tenantId, "isTest", isTest));
    }

    // Not transactional: WalletService commits in its own transaction, and the audit row follows the posting.
    public WalletEntryResponse adjust(String tenantId, WalletAdjustRequest request) {
        WalletEntry entry = walletService.adjust(tenantId, request.getAmount(), request.getReason(), "adj:" + request.getRequestId());
        auditService.record(PlatformAuditAction.WALLET_ADJUST, "Wallet", entry.getWalletId(),
                Map.of("tenantId", tenantId, "amount", request.getAmount().toPlainString(), "reason", request.getReason(),
                        "entryId", entry.getId()));
        return toResponse(entry);
    }

    public WalletEntryResponse close(String tenantId, String utr) {
        WalletEntry entry = walletService.close(tenantId, utr);
        auditService.record(PlatformAuditAction.WALLET_CLOSE, "Wallet", entry.getWalletId(),
                Map.of("tenantId", tenantId, "utr", utr, "entryId", entry.getId()));
        return toResponse(entry);
    }

    private Wallet find(String tenantId) {
        return walletRepository.findByTenantId(tenantId).orElseThrow(() -> new NotFoundException("Wallet not found for this agency"));
    }

    private Wallet lock(String tenantId) {
        return walletRepository.findByTenantIdForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Wallet not found for this agency"));
    }

    private static WalletEntryResponse toResponse(WalletEntry e) {
        return WalletEntryResponse.builder().id(e.getId()).type(e.getType().name()).amount(e.getAmount())
                .balanceAfter(e.getBalanceAfter()).heldAfter(e.getHeldAfter())
                .availableAfter(e.getBalanceAfter().subtract(e.getHeldAfter()))
                .narration(e.getNarration()).refType(e.getRefType()).refId(e.getRefId())
                .createdByName(e.getCreatedByName()).createdAt(e.getCreatedAt()).build();
    }

    /** Stops spreadsheet apps running a typed narration or name as a formula. */
    private static String safe(String value) {
        return value != null && !value.isEmpty() && "=+@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
