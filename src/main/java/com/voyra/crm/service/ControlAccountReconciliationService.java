package com.voyra.crm.service;

import com.voyra.crm.dto.ControlAccountReconciliationRowResponse;
import com.voyra.crm.entity.CustomerLedgerEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierLedgerEntry;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Rule 1.7.1/1.7.2 - the entire risk mitigation for adding a general ledger to a system that
 * already holds live money. Every GL balance here will legitimately read zero until the
 * posting-rule-table events are wired into the existing services; this computation is still
 * written correctly now so it is immediately meaningful the moment the first posting site lands,
 * per the standing Rule 10.1 dual-run gate (no GL-derived statement is shown to a user until this
 * endpoint reports zero variance across a full financial period).
 */
@Service
@RequiredArgsConstructor
public class ControlAccountReconciliationService {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalLineRepository journalLineRepository;
    private final CustomerLedgerEntryRepository customerLedgerEntryRepository;
    private final SupplierLedgerEntryRepository supplierLedgerEntryRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;

    @Transactional(readOnly = true)
    public List<ControlAccountReconciliationRowResponse> reconcile() {
        return List.of(
                row(SystemAccount.ACCOUNTS_RECEIVABLE, this::accountsReceivableSubsidiaryTotal),
                row(SystemAccount.ACCOUNTS_PAYABLE, this::accountsPayableSubsidiaryTotal),
                row(SystemAccount.CLIENT_ADVANCES_HELD, this::clientAdvancesHeldSubsidiaryTotal),
                row(SystemAccount.SUPPLIER_ADVANCES, this::supplierAdvancesSubsidiaryTotal)
        );
    }

    private ControlAccountReconciliationRowResponse row(SystemAccount account, java.util.function.Supplier<BigDecimal> subsidiary) {
        Optional<LedgerAccount> ledgerAccount = ledgerAccountRepository.findByCode(account.code());
        BigDecimal glBalance = ledgerAccount.map(this::glBalance).orElse(BigDecimal.ZERO);
        BigDecimal subsidiaryTotal = subsidiary.get();
        return ControlAccountReconciliationRowResponse.builder()
                .accountCode(account.code())
                .accountName(account.accountName())
                .glBalanceInr(glBalance)
                .subsidiaryTotalInr(subsidiaryTotal)
                .varianceInr(glBalance.subtract(subsidiaryTotal))
                .build();
    }

    /** ASSET-type control: balance = debit - credit. LIABILITY-type control: balance = credit - debit. */
    private BigDecimal glBalance(LedgerAccount account) {
        List<JournalLine> lines = journalLineRepository.findByAccountCode(account.getCode());
        BigDecimal debit = lines.stream().map(JournalLine::getDebitAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = lines.stream().map(JournalLine::getCreditAmountInr).reduce(BigDecimal.ZERO, BigDecimal::add);
        return account.getAccountType() == LedgerAccountType.ASSET ? debit.subtract(credit) : credit.subtract(debit);
    }

    /** Mirrors InvoiceDocumentService#postInvoiceRaised's own comment: SUM(debit_inr - credit_inr) == SUM(balance_due_inr) across every client. */
    private BigDecimal accountsReceivableSubsidiaryTotal() {
        return customerLedgerEntryRepository.findAll().stream()
                .map(e -> e.getDebitAmountInr().subtract(e.getCreditAmountInr()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Mirrors SupplierLedgerEntry's own sign convention javadoc: CREDIT raises payable, DEBIT
     * lowers it. Excludes ADVANCE_PAID: an advance is a prepayment asset against account 1300,
     * not a bill liability against 2200, even though {@code SupplierLedgerService} posts it to
     * the same per-vendor ledger for the unified-balance view. Without this exclusion, every
     * advance bleeds into this total (it is already counted correctly, on its own, by
     * {@link #supplierAdvancesSubsidiaryTotal}), understating what the agency actually owes its
     * suppliers on open bills and permanently preventing account 2200 from reconciling to zero
     * variance - caught by posting a live advance payment against a real tenant and watching the
     * control-account reconciliation move by the advance amount.
     */
    private BigDecimal accountsPayableSubsidiaryTotal() {
        return supplierLedgerEntryRepository.findAll().stream()
                .filter((SupplierLedgerEntry e) -> e.getEntryType() != SupplierLedgerEntryType.ADVANCE_PAID)
                .map((SupplierLedgerEntry e) -> e.getCreditAmountInr().subtract(e.getDebitAmountInr()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The customer wallet, globally: cash held with no invoice against it, minus what has already been drawn down - same shape as the supplier-side advance pool. */
    private BigDecimal clientAdvancesHeldSubsidiaryTotal() {
        BigDecimal deposited = paymentReceiptRepository.findAll().stream()
                .filter((PaymentReceipt r) -> Boolean.TRUE.equals(r.getIsAdvance()) && r.getInvoiceId() == null && r.getReversedAt() == null)
                .map(PaymentReceipt::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applied = paymentReceiptRepository.findAll().stream()
                .filter((PaymentReceipt r) -> Boolean.TRUE.equals(r.getAppliedFromAdvance()) && r.getReversedAt() == null)
                .map(PaymentReceipt::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return deposited.subtract(applied);
    }

    /** Mirrors SupplierPaymentService#applyAdvance's own "remaining pool" computation, done globally instead of per vendor. */
    private BigDecimal supplierAdvancesSubsidiaryTotal() {
        BigDecimal paid = supplierPaymentRepository.findAll().stream()
                .filter((SupplierPayment p) -> Boolean.TRUE.equals(p.getIsAdvance()) && p.getReversedAt() == null)
                .map(SupplierPayment::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applied = supplierPaymentRepository.findAll().stream()
                .filter((SupplierPayment p) -> Boolean.TRUE.equals(p.getAppliedFromAdvance()) && p.getReversedAt() == null)
                .map(SupplierPayment::getAmountInr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return paid.subtract(applied);
    }
}
