package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformReceivable;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.entity.WalletEntry;
import com.voyra.crm.enums.ReceivableReason;
import com.voyra.crm.enums.ReceivableStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletEntryType;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.InsufficientBalanceException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.exception.ReceivableDueException;
import com.voyra.crm.models.WalletPosting;
import com.voyra.crm.repository.PlatformReceivableRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.WalletMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The only writer of wallet money. Every mutation runs in its own transaction (REQUIRES_NEW, so a debit commits
 * before any tenant-side mirror), takes the wallet row lock first, and only then checks the idempotency key, so
 * a concurrent retry with the same key waits for the first and gets its entry back instead of posting twice.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final Set<WalletEntryType> CREDIT_TYPES = EnumSet.of(WalletEntryType.TOPUP,
            WalletEntryType.COMMISSION_CREDIT, WalletEntryType.REFUND_CREDIT, WalletEntryType.ADJUSTMENT);
    private static final Set<WalletEntryType> DEBIT_OR_RECEIVABLE_TYPES = EnumSet.of(WalletEntryType.COMMISSION_RECALL,
            WalletEntryType.TDS_DEDUCTED, WalletEntryType.ADJUSTMENT);
    private static final String RECEIVABLE_REF = "PLATFORM_RECEIVABLE";

    private final WalletRepository walletRepository;
    private final WalletEntryRepository entryRepository;
    private final PlatformReceivableRepository receivableRepository;
    private final TopupRequestRepository topupRequestRepository;
    private final TenantRepository tenantRepository;
    private final NotificationService notificationService;

    /** Credits {@code amount}, then settles DUE receivables oldest first out of it; returns the credit entry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry credit(String tenantId, WalletEntryType type, BigDecimal amount, String idempotencyKey,
                              String refType, String refId, String narration) {
        requireType(type, CREDIT_TYPES);
        requirePositive(amount);
        Wallet wallet = lock(tenantId);
        Optional<WalletEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireOpen(wallet);
        WalletEntry entry = creditLocked(wallet, new WalletPosting(type, amount, idempotencyKey, refType, refId, narration, null));
        checkLowBalance(wallet);
        return entry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry hold(String tenantId, BigDecimal amount, String idempotencyKey, String refType, String refId) {
        requirePositive(amount);
        Wallet wallet = lock(tenantId);
        Optional<WalletEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireOpen(wallet);
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("Agency not found"));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("This agency is suspended or deactivated - bookings are not allowed");
        }
        if (Boolean.TRUE.equals(tenant.getBookingBlocked())) {
            throw new IllegalStateException("Bookings are blocked for this agency - contact Voyra");
        }
        if (hasDueReceivable(tenantId)) {
            throw new ReceivableDueException();
        }
        BigDecimal available = available(wallet);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(available);
        }
        WalletEntry entry = post(wallet, new WalletPosting(WalletEntryType.HOLD, amount, idempotencyKey, refType, refId, null, null));
        checkLowBalance(wallet);
        return entry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry releaseHold(String holdEntryId, String idempotencyKey) {
        WalletEntry hold = loadHold(holdEntryId);
        Wallet wallet = lock(hold.getTenantId());
        Optional<WalletEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (isReleased(hold)) {
            throw new IllegalStateException("This hold is already released");
        }
        WalletEntry entry = release(wallet, hold, idempotencyKey);
        checkLowBalance(wallet);
        return entry;
    }

    /**
     * Releases the hold (if still open) and debits {@code amount} once. A debit above what is available after the
     * release - a late ticket after an admin release, or a supplier charging more than the hold - debits what is
     * there and opens a LATE_TICKET receivable for the shortfall.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry debitHeld(String holdEntryId, BigDecimal amount, String idempotencyKey, String refType, String refId) {
        requirePositive(amount);
        WalletEntry hold = loadHold(holdEntryId);
        Wallet wallet = lock(hold.getTenantId());
        Optional<WalletEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!isReleased(hold)) {
            release(wallet, hold, idempotencyKey + ":release");
        }
        WalletEntry entry = debitCovered(wallet, WalletEntryType.BOOKING_DEBIT, amount, idempotencyKey, refType, refId,
                ReceivableReason.LATE_TICKET);
        checkLowBalance(wallet);
        return entry;
    }

    /** Recalls, TDS and chargebacks: posts what available covers and opens a DUE receivable for the rest. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry debitOrReceivable(String tenantId, WalletEntryType type, BigDecimal amount, String idempotencyKey,
                                         String refType, String refId, ReceivableReason reason) {
        requireType(type, DEBIT_OR_RECEIVABLE_TYPES);
        requirePositive(amount);
        Wallet wallet = lock(tenantId);
        Optional<WalletEntry> existing = entryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        WalletEntry entry = debitCovered(wallet, type, amount, idempotencyKey, refType, refId, reason);
        checkLowBalance(wallet);
        return entry;
    }

    /** Admin correction. shortcut: a random idempotency key, so a double-submit posts twice; the caller's audit row shows it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry adjust(String tenantId, BigDecimal signedAmount, String reason) {
        if (signedAmount == null || signedAmount.signum() == 0) {
            throw new IllegalArgumentException("Adjustment amount must not be zero");
        }
        requireScale(signedAmount);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for an adjustment");
        }
        Wallet wallet = lock(tenantId);
        requireOpen(wallet);
        String key = "adj:" + UniqueIdResolver.resolve(entryRepository::existsById);
        WalletPosting posting = new WalletPosting(WalletEntryType.ADJUSTMENT, signedAmount, key, null, null, reason, null);
        WalletEntry entry;
        if (signedAmount.signum() > 0) {
            entry = creditLocked(wallet, posting);
        } else {
            BigDecimal available = available(wallet);
            if (available.add(signedAmount).signum() < 0) {
                throw new InsufficientBalanceException(available);
            }
            entry = post(wallet, posting);
        }
        checkLowBalance(wallet);
        return entry;
    }

    /** Pays the whole balance out (CLOSURE_REFUND, by bank transfer {@code utr}) and closes the wallet for good. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WalletEntry close(String tenantId, String utr) {
        Wallet wallet = lock(tenantId);
        String key = "close:" + wallet.getId();
        Optional<WalletEntry> existing = entryRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireOpen(wallet);
        int openHolds = entryRepository.findOpenHolds(wallet.getId()).size();
        if (openHolds > 0) {
            throw new IllegalStateException("Cannot close the wallet: " + openHolds + " booking hold(s) are still open");
        }
        if (hasDueReceivable(tenantId)) {
            throw new IllegalStateException("Cannot close the wallet: a receivable is still due to Voyra");
        }
        if (topupRequestRepository.existsByWalletIdAndStatus(wallet.getId(), TopupStatus.PENDING)) {
            throw new IllegalStateException("Cannot close the wallet: a top-up request is still pending");
        }
        if (wallet.getBalance().signum() > 0 && (utr == null || utr.isBlank())) {
            throw new IllegalArgumentException("The bank UTR of the closure refund is required");
        }
        WalletEntry entry = post(wallet, new WalletPosting(WalletEntryType.CLOSURE_REFUND, wallet.getBalance().negate(),
                key, null, null, utr == null ? "Wallet closed" : "Closure refund, UTR " + utr, null));
        wallet.setStatus(WalletStatus.CLOSED);
        wallet.setClosedAt(Instant.now());
        wallet.setClosedBy(SecurityContextUtil.getCurrentUserId().orElse("system"));
        wallet.setClosureUtr(utr);
        walletRepository.save(wallet);
        return entry;
    }

    @Transactional(readOnly = true)
    public boolean hasDueReceivable(String tenantId) {
        return receivableRepository.existsByTenantIdAndStatus(tenantId, ReceivableStatus.DUE);
    }

    private WalletEntry creditLocked(Wallet wallet, WalletPosting credit) {
        WalletEntry entry = post(wallet, credit);
        BigDecimal budget = credit.amount();
        for (PlatformReceivable receivable : receivableRepository
                .findByTenantIdAndStatusOrderByCreatedAt(wallet.getTenantId(), ReceivableStatus.DUE)) {
            if (budget.signum() <= 0) {
                break;
            }
            BigDecimal settle = budget.min(receivable.getAmount());
            post(wallet, new WalletPosting(WalletEntryType.RECEIVABLE_SETTLED, settle.negate(),
                    credit.idempotencyKey() + ":rs:" + receivable.getId(), RECEIVABLE_REF, receivable.getId(),
                    "Settles " + receivable.getReason(), entry.getId()));
            budget = budget.subtract(settle);
            receivable.setAmount(receivable.getAmount().subtract(settle));
            if (receivable.getAmount().signum() == 0) {
                receivable.setStatus(ReceivableStatus.SETTLED);
                receivable.setSettledAt(Instant.now());
            }
            receivableRepository.save(receivable);
        }
        return entry;
    }

    private WalletEntry debitCovered(Wallet wallet, WalletEntryType type, BigDecimal amount, String idempotencyKey,
                                     String refType, String refId, ReceivableReason reason) {
        BigDecimal covered = amount.min(available(wallet));
        BigDecimal shortfall = amount.subtract(covered);
        WalletEntry entry = post(wallet, new WalletPosting(type, covered.negate(), idempotencyKey, refType, refId,
                shortfall.signum() > 0 ? "Short by " + shortfall + " - receivable opened" : null, null));
        if (shortfall.signum() > 0) {
            receivableRepository.save(PlatformReceivable.builder()
                    .id(UniqueIdResolver.resolve(receivableRepository::existsById))
                    .tenantId(wallet.getTenantId()).amount(shortfall).originalAmount(shortfall)
                    .reason(reason).status(ReceivableStatus.DUE).refType(refType).refId(refId)
                    .createdAt(Instant.now()).build());
            notificationService.platformAlert("Wallet receivable opened",
                    "Agency " + wallet.getTenantId() + " is short by " + shortfall + " on " + type + " (" + reason + ")");
        }
        return entry;
    }

    private WalletEntry release(Wallet wallet, WalletEntry hold, String idempotencyKey) {
        return post(wallet, new WalletPosting(WalletEntryType.HOLD_RELEASE, hold.getAmount().negate(), idempotencyKey,
                hold.getRefType(), hold.getRefId(), null, hold.getId()));
    }

    private WalletEntry post(Wallet wallet, WalletPosting p) {
        WalletMath.State next = WalletMath.apply(wallet.getBalance(), wallet.getHeld(), p.type(), p.amount());
        wallet.setBalance(next.balance());
        wallet.setHeld(next.held());
        walletRepository.save(wallet);
        Optional<CustomUserPrincipal> user = SecurityContextUtil.getCurrentUser();
        return entryRepository.save(WalletEntry.builder()
                .id(UniqueIdResolver.resolve(entryRepository::existsById))
                .walletId(wallet.getId()).tenantId(wallet.getTenantId())
                .type(p.type()).amount(p.amount())
                .balanceAfter(next.balance()).heldAfter(next.held())
                .relatedEntryId(p.relatedEntryId()).refType(p.refType()).refId(p.refId())
                .idempotencyKey(p.idempotencyKey()).narration(p.narration())
                .createdBy(user.map(CustomUserPrincipal::userId).orElse("system"))
                .createdByName(user.map(CustomUserPrincipal::username).orElse("Voyra"))
                .createdAt(Instant.now())
                .build());
    }

    /** Notifies once when available drops below the threshold; re-arms when it climbs back to or above it. */
    private void checkLowBalance(Wallet wallet) {
        BigDecimal threshold = wallet.getLowBalanceThreshold();
        if (threshold == null) {
            return;
        }
        BigDecimal available = available(wallet);
        if (available.compareTo(threshold) >= 0) {
            wallet.setLowBalanceNotifiedAt(null);
        } else if (wallet.getLowBalanceNotifiedAt() == null) {
            wallet.setLowBalanceNotifiedAt(Instant.now());
            notificationService.lowBalance(wallet.getTenantId(), available);
        }
        walletRepository.save(wallet);
    }

    private Wallet lock(String tenantId) {
        return walletRepository.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new NotFoundException("Wallet not found for this agency"));
    }

    private WalletEntry loadHold(String holdEntryId) {
        WalletEntry hold = entryRepository.findById(holdEntryId)
                .orElseThrow(() -> new NotFoundException("Wallet hold not found"));
        if (hold.getType() != WalletEntryType.HOLD) {
            throw new IllegalArgumentException("Wallet entry " + holdEntryId + " is not a hold");
        }
        return hold;
    }

    private boolean isReleased(WalletEntry hold) {
        return entryRepository.existsByRelatedEntryIdAndType(hold.getId(), WalletEntryType.HOLD_RELEASE);
    }

    private static BigDecimal available(Wallet wallet) {
        return wallet.getBalance().subtract(wallet.getHeld());
    }

    private static void requireOpen(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.OPEN) {
            throw new IllegalStateException("This wallet is closed");
        }
    }

    private static void requireType(WalletEntryType type, Set<WalletEntryType> allowed) {
        if (!allowed.contains(type)) {
            throw new IllegalArgumentException("Wallet entry type " + type + " is not allowed here");
        }
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        requireScale(amount);
    }

    // The columns are NUMERIC(19,2): a third decimal would round in the database and drift from balance_after.
    private static void requireScale(BigDecimal amount) {
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most two decimal places");
        }
    }
}
