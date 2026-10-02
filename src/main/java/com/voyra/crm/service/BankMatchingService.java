package com.voyra.crm.service;

import com.voyra.crm.dto.BankTransactionResponse;
import com.voyra.crm.dto.PaymentReceiptRequest;
import com.voyra.crm.dto.SupplierPaymentRequest;
import com.voyra.crm.dto.TierTwoCandidateResponse;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.entity.BankTransaction;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.BankMatchedSourceType;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.enums.BankTransactionMatchStatus;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.BankAccountRepository;
import com.voyra.crm.repository.BankMatchRuleRepository;
import com.voyra.crm.repository.BankTransactionRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import com.voyra.crm.security.SecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rule 4.3 - the two-tier match engine. Tier 1 is exact and auto-confirmable at import time;
 * tier 2 is a scored candidate a human must confirm (Rule 4.3.1 - never auto-applied). Rule 4.3.2
 * is the load-bearing constraint on this whole class: matching never posts directly - it always
 * calls through {@link PaymentReceiptService#record} or {@link SupplierPaymentService#pay}, the
 * exact same path a human using the accounts screens would take. There is no second posting path
 * into the ledger anywhere in this file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankMatchingService {

    private static final List<InvoiceLifecycle> OPEN_INVOICE_STATUSES = List.of(InvoiceLifecycle.ISSUED, InvoiceLifecycle.PARTIALLY_PAID);
    private static final List<SupplierInvoiceStatus> OPEN_BILL_STATUSES = List.of(SupplierInvoiceStatus.APPROVED, SupplierInvoiceStatus.PARTIALLY_PAID);
    private static final int TIER2_WINDOW_DAYS = 2;

    private final BankTransactionRepository bankTransactionRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BankMatchRuleRepository bankMatchRuleRepository;
    private final InvoiceRepository invoiceRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final PaymentReceiptService paymentReceiptService;
    private final SupplierPaymentService supplierPaymentService;
    private final JournalService journalService;

    /** Called from the same transaction that persists the imported lines (Rule 4.2's phase 3). */
    @Transactional
    public int runTier1(List<BankTransaction> transactions) {
        int matched = 0;
        for (BankTransaction txn : transactions) {
            if (txn.getDirection() == BankTransactionDirection.CREDIT) {
                Optional<Invoice> invoice = findExactInvoiceMatch(txn);
                if (invoice.isPresent()) {
                    postReceiptAndMarkMatched(txn, invoice.get());
                    matched++;
                }
            } else {
                Optional<SupplierInvoice> bill = findExactBillMatch(txn);
                if (bill.isPresent()) {
                    postPaymentAndMarkMatched(txn, bill.get());
                    matched++;
                }
            }
        }
        return matched;
    }

    @Transactional(readOnly = true)
    public List<BankTransactionResponse> listUnmatched(String bankAccountId) {
        return bankTransactionRepository.findByBankAccountIdAndMatchStatus(bankAccountId, BankTransactionMatchStatus.UNMATCHED)
                .stream().map(BankMatchingService::toResponse).toList();
    }

    private static BankTransactionResponse toResponse(BankTransaction t) {
        return BankTransactionResponse.builder()
                .id(t.getId()).txnDate(t.getTxnDate()).description(t.getDescription())
                .bankReference(t.getBankReference()).amount(t.getAmount()).direction(t.getDirection())
                .matchStatus(t.getMatchStatus()).matchedSourceType(t.getMatchedSourceType())
                .matchedSourceId(t.getMatchedSourceId())
                .build();
    }

    /** Rule 4.3.1 - read-only. Nothing here writes anything; a candidate is confirmed via {@link #confirmMatch}. */
    @Transactional(readOnly = true)
    public List<TierTwoCandidateResponse> listTier2Candidates(String bankAccountId) {
        List<TierTwoCandidateResponse> candidates = new ArrayList<>();
        for (BankTransaction txn : bankTransactionRepository.findByBankAccountIdAndMatchStatus(
                bankAccountId, BankTransactionMatchStatus.UNMATCHED)) {
            if (txn.getDirection() == BankTransactionDirection.CREDIT) {
                for (Invoice invoice : invoiceRepository.findByStatusInAndBalanceDueInr(OPEN_INVOICE_STATUSES, txn.getAmount())) {
                    if (withinWindow(txn, invoice.getDueDate())) {
                        candidates.add(TierTwoCandidateResponse.builder()
                                .transactionId(txn.getId()).sourceType(BankMatchedSourceType.INVOICE)
                                .sourceId(invoice.getId()).sourceLabel(invoice.getInvoiceNumber())
                                .amount(txn.getAmount()).score(score(txn, invoice.getDueDate())).build());
                    }
                }
            } else {
                for (SupplierInvoice bill : supplierInvoiceRepository.findByStatusInAndBalanceDueInr(OPEN_BILL_STATUSES, txn.getAmount())) {
                    if (withinWindow(txn, bill.getDueDate())) {
                        candidates.add(TierTwoCandidateResponse.builder()
                                .transactionId(txn.getId()).sourceType(BankMatchedSourceType.SUPPLIER_INVOICE)
                                .sourceId(bill.getId()).sourceLabel(bill.getSupplierInvoiceNumber())
                                .amount(txn.getAmount()).score(score(txn, bill.getDueDate())).build());
                    }
                }
            }
        }
        return candidates;
    }

    /** The only path a tier-2 candidate (or a manual pick) ever posts through (Rule 4.3.2). */
    @Transactional
    public void confirmMatch(String transactionId, BankMatchedSourceType sourceType, String sourceId) {
        BankTransaction txn = bankTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Bank transaction not found: " + transactionId));
        if (txn.getMatchStatus() != BankTransactionMatchStatus.UNMATCHED
                && txn.getMatchStatus() != BankTransactionMatchStatus.TIER2_CANDIDATE) {
            throw new IllegalStateException("This transaction is already " + txn.getMatchStatus());
        }

        if (sourceType == BankMatchedSourceType.INVOICE) {
            Invoice invoice = invoiceRepository.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + sourceId));
            postReceiptAndMarkMatched(txn, invoice);
            txn.setMatchStatus(BankTransactionMatchStatus.CONFIRMED);
            bankTransactionRepository.save(txn);
        } else if (sourceType == BankMatchedSourceType.SUPPLIER_INVOICE) {
            SupplierInvoice bill = supplierInvoiceRepository.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Supplier bill not found: " + sourceId));
            postPaymentAndMarkMatched(txn, bill);
            txn.setMatchStatus(BankTransactionMatchStatus.CONFIRMED);
            bankTransactionRepository.save(txn);
        } else {
            throw new IllegalArgumentException("Cannot confirm against source type " + sourceType);
        }
    }

    /** Rule 4.3.3 - only reaches transactions tier 1 and tier 2 left untouched. */
    @Transactional
    public int applyMatchRules(String bankAccountId) {
        List<BankMatchRule> rules = bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc();
        int applied = 0;
        for (BankTransaction txn : bankTransactionRepository.findByBankAccountIdAndMatchStatus(
                bankAccountId, BankTransactionMatchStatus.UNMATCHED)) {
            for (BankMatchRule rule : rules) {
                String haystack = rule.getMatchField() == com.voyra.crm.enums.BankMatchField.BANK_REFERENCE
                        ? txn.getBankReference() : txn.getDescription();
                if (haystack == null || !haystack.toUpperCase().contains(rule.getPattern().toUpperCase())) {
                    continue;
                }
                if (Boolean.TRUE.equals(rule.getAutoPost())) {
                    postBankChargeJournal(txn, rule);
                    txn.setMatchStatus(BankTransactionMatchStatus.CATEGORIZED);
                } else {
                    txn.setMatchStatus(BankTransactionMatchStatus.CATEGORIZED);
                }
                txn.setMatchedSourceType(BankMatchedSourceType.BANK_MATCH_RULE);
                txn.setMatchedSourceId(rule.getId());
                txn.setMatchedAt(LocalDateTime.now());
                txn.setMatchedBy(currentUserId());
                bankTransactionRepository.save(txn);
                applied++;
                break;
            }
        }
        return applied;
    }

    private Optional<Invoice> findExactInvoiceMatch(BankTransaction txn) {
        if (txn.getBankReference() != null && !txn.getBankReference().isBlank()) {
            Optional<Invoice> byRef = invoiceRepository.findByInvoiceNumber(txn.getBankReference().trim());
            if (byRef.isPresent() && byRef.get().getBalanceDueInr().compareTo(txn.getAmount()) == 0
                    && OPEN_INVOICE_STATUSES.contains(byRef.get().getStatus())) {
                return byRef;
            }
        }
        List<Invoice> byAmount = invoiceRepository.findByStatusInAndBalanceDueInr(OPEN_INVOICE_STATUSES, txn.getAmount());
        return byAmount.stream()
                .filter(i -> containsNumber(txn.getDescription(), i.getInvoiceNumber()) || containsNumber(txn.getBankReference(), i.getInvoiceNumber()))
                .findFirst();
    }

    private Optional<SupplierInvoice> findExactBillMatch(BankTransaction txn) {
        if (txn.getBankReference() != null && !txn.getBankReference().isBlank()) {
            Optional<SupplierInvoice> byRef = supplierInvoiceRepository.findBySupplierInvoiceNumber(txn.getBankReference().trim());
            if (byRef.isPresent() && byRef.get().getBalanceDueInr().compareTo(txn.getAmount()) == 0
                    && OPEN_BILL_STATUSES.contains(byRef.get().getStatus())) {
                return byRef;
            }
        }
        List<SupplierInvoice> byAmount = supplierInvoiceRepository.findByStatusInAndBalanceDueInr(OPEN_BILL_STATUSES, txn.getAmount());
        return byAmount.stream()
                .filter(b -> containsNumber(txn.getDescription(), b.getSupplierInvoiceNumber()) || containsNumber(txn.getBankReference(), b.getSupplierInvoiceNumber()))
                .findFirst();
    }

    private static boolean containsNumber(String haystack, String number) {
        return haystack != null && number != null && !number.isBlank()
                && haystack.toUpperCase().contains(number.toUpperCase());
    }

    private boolean withinWindow(BankTransaction txn, java.time.LocalDate dueDate) {
        if (dueDate == null) {
            return true;
        }
        return Math.abs(ChronoUnit.DAYS.between(dueDate, txn.getTxnDate())) <= TIER2_WINDOW_DAYS;
    }

    private BigDecimal score(BankTransaction txn, java.time.LocalDate dueDate) {
        if (dueDate == null) {
            return new BigDecimal("50.00");
        }
        long daysOff = Math.abs(ChronoUnit.DAYS.between(dueDate, txn.getTxnDate()));
        return BigDecimal.valueOf(100 - (daysOff * 25)).setScale(2);
    }

    /** Posts the real receipt via {@link PaymentReceiptService#record} - this IS the posting (Rule 4.3.2), not a second path. */
    private void postReceiptAndMarkMatched(BankTransaction txn, Invoice invoice) {
        PaymentReceiptRequest request = new PaymentReceiptRequest();
        request.setInvoiceId(invoice.getId());
        request.setAmount(invoice.getBalanceDue());
        request.setPaymentMode(PaymentMode.BANK_TRANSFER);
        request.setInstrumentRef(txn.getBankReference());
        request.setBankAccountLabel(bankAccountRepository.findById(txn.getBankAccountId()).map(com.voyra.crm.entity.BankAccount::getAccountName).orElse(null));
        request.setReceivedOn(txn.getTxnDate());
        var response = paymentReceiptService.record(request);

        paymentReceiptRepository.findById(response.getId()).ifPresent(receipt -> {
            receipt.setBankAccountId(txn.getBankAccountId());
            paymentReceiptRepository.save(receipt);
        });

        txn.setMatchStatus(BankTransactionMatchStatus.TIER1_MATCHED);
        txn.setMatchedSourceType(BankMatchedSourceType.INVOICE);
        txn.setMatchedSourceId(response.getId());
        txn.setMatchedAt(LocalDateTime.now());
        txn.setMatchedBy(currentUserId());
        bankTransactionRepository.save(txn);
    }

    /** Posts the real payment via {@link SupplierPaymentService#pay} - this IS the posting (Rule 4.3.2), not a second path. */
    private void postPaymentAndMarkMatched(BankTransaction txn, SupplierInvoice bill) {
        SupplierPaymentRequest request = new SupplierPaymentRequest();
        request.setVendorId(bill.getVendorId());
        request.setSupplierInvoiceId(bill.getId());
        request.setAmount(bill.getBalanceDue());
        request.setPaymentMode(PaymentMode.BANK_TRANSFER);
        request.setInstrumentRef(txn.getBankReference());
        request.setBankAccountLabel(bankAccountRepository.findById(txn.getBankAccountId()).map(com.voyra.crm.entity.BankAccount::getAccountName).orElse(null));
        request.setPaidOn(txn.getTxnDate());
        var response = supplierPaymentService.pay(request);

        supplierPaymentRepository.findById(response.getId()).ifPresent(payment -> {
            payment.setBankAccountId(txn.getBankAccountId());
            supplierPaymentRepository.save(payment);
        });

        txn.setMatchStatus(BankTransactionMatchStatus.TIER1_MATCHED);
        txn.setMatchedSourceType(BankMatchedSourceType.SUPPLIER_INVOICE);
        txn.setMatchedSourceId(response.getId());
        txn.setMatchedAt(LocalDateTime.now());
        txn.setMatchedBy(currentUserId());
        bankTransactionRepository.save(txn);
    }

    /** Rule 4.3.3 + posting-rule-table row 16 - the one case this class posts directly, because a bank charge has no other document to post through. */
    private void postBankChargeJournal(BankTransaction txn, BankMatchRule rule) {
        BigDecimal amountInr = txn.getAmount();
        String narration = "Bank charge categorised by rule '" + rule.getName() + "': " + txn.getDescription();
        List<JournalLinePosting> lines = txn.getDirection() == BankTransactionDirection.DEBIT
                ? List.of(JournalLinePosting.debit(rule.getTargetAccountCode(), amountInr, narration),
                          JournalLinePosting.credit(SystemAccount.BANK_ACCOUNTS.code(), amountInr, narration))
                : List.of(JournalLinePosting.debit(SystemAccount.BANK_ACCOUNTS.code(), amountInr, narration),
                          JournalLinePosting.credit(rule.getTargetAccountCode(), amountInr, narration));

        journalService.post(new JournalPosting(
                txn.getTxnDate(), JournalSourceType.BANK_TRANSACTION, txn.getId(),
                JournalPurpose.BANK_CHARGE_CATEGORIZED, narration, null, null, lines));
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }
}
