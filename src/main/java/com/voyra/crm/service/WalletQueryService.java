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
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.repository.PlatformBookingRepository;
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

    private static final String BOOKING_REF = "PLATFORM_BOOKING";

    private final WalletRepository walletRepository;
    private final WalletEntryRepository entryRepository;
    private final PlatformReceivableRepository receivableRepository;
    private final TopupRequestRepository topupRequestRepository;
    private final WalletService walletService;
    private final PlatformBookingRepository platformBookingRepository;
    private final PlatformAuditService auditService;

    @Transactional(readOnly = true)
    public WalletSummaryResponse summary(String tenantId) {
        Wallet w = find(tenantId);
        List<ReceivableResponse> due = receivableRepository
                .findByTenantIdAndStatusOrderByCreatedAt(tenantId, ReceivableStatus.DUE).stream()
                .map(r -> ReceivableResponse.builder().id(r.getId()).amount(r.getAmount())
                        .originalAmount(r.getOriginalAmount()).reason(r.getReason().name()).reasonText(r.getReason().text()).createdAt(r.getCreatedAt()).build())
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
        var page = entryRepository.findPagedByWalletIdOrderByCreatedAtDescIdDesc(w.getId(), pageable);
        Map<String, String> bookings = bookingRefs(page.getContent());
        return PagedResponse.from(page, e -> toResponse(e, bookings));
    }

    @Transactional(readOnly = true)
    public String csv(String tenantId) {
        Wallet w = find(tenantId);
        List<WalletEntry> all = entryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(w.getId(), Pageable.unpaged());
        Map<String, String> bookings = bookingRefs(all);
        List<List<String>> rows = all.stream()
                .map(e -> toResponse(e, bookings))
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
        return toResponse(e, Map.of());
    }

    /** "flight booking MKK4EK" (or without the PNR before ticketing) per platform booking these entries point at. */
    private Map<String, String> bookingRefs(List<WalletEntry> entries) {
        List<String> ids = entries.stream().filter(e -> BOOKING_REF.equals(e.getRefType()) && e.getRefId() != null)
                .map(WalletEntry::getRefId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.HashMap<>();
        platformBookingRepository.findAllById(ids).forEach(b -> out.put(b.getId(),
                (b.getProduct() == PricingProduct.HOTEL ? "hotel booking" : "flight booking") + (b.getPnr() == null ? "" : " " + b.getPnr())));
        return out;
    }

    /** Booking postings made without a narration (hold, release, debit, recall, TDS) read in words, not "-". */
    private static String narration(WalletEntry e, Map<String, String> bookings) {
        String ref = BOOKING_REF.equals(e.getRefType()) ? bookings.get(e.getRefId()) : null;
        if (e.getNarration() != null || ref == null) {
            return e.getNarration();
        }
        return switch (e.getType()) {
            case HOLD -> "Held for " + ref;
            case HOLD_RELEASE -> "Hold released for " + ref;
            case BOOKING_DEBIT -> "Paid for " + ref;
            case COMMISSION_CREDIT -> "Commission on " + ref;
            case TDS_DEDUCTED -> "TDS on the commission for " + ref;
            case REFUND_CREDIT -> "Refund on cancelled " + ref;
            case COMMISSION_RECALL -> "Commission taken back on cancelled " + ref;
            default -> null;
        };
    }

    private static WalletEntryResponse toResponse(WalletEntry e, Map<String, String> bookings) {
        return WalletEntryResponse.builder().id(e.getId()).type(e.getType().name()).amount(e.getAmount())
                .balanceAfter(e.getBalanceAfter()).heldAfter(e.getHeldAfter())
                .availableAfter(e.getBalanceAfter().subtract(e.getHeldAfter()))
                .narration(narration(e, bookings)).refType(e.getRefType()).refId(e.getRefId())
                .createdByName(e.getCreatedByName()).createdAt(e.getCreatedAt()).build();
    }

    /** Stops spreadsheet apps running a typed narration or name as a formula. */
    private static String safe(String value) {
        return value != null && !value.isEmpty() && "=+@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}
