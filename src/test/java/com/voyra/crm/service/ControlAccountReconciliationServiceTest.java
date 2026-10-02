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
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.SupplierPaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Gate 2.1 / Rule 1.7.1-1.7.2 - the one test that actually proves the GL is safe to build
 * statements on. {@code ControlAccountReconciliationService.reconcile()} compares each control
 * account's GL balance against the independently-computed subsidiary-ledger total for the same
 * events; this test seeds both sides of all four control accounts from the SAME underlying
 * business facts (a receipt against an invoice, a bill paid, a client deposit partly drawn down,
 * a supplier advance partly applied) and asserts every row's {@code varianceInr} is exactly zero.
 * A second test perturbs one side only, to prove the assertion actually discriminates rather than
 * trivially passing.
 */
@ExtendWith(MockitoExtension.class)
class ControlAccountReconciliationServiceTest {

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private CustomerLedgerEntryRepository customerLedgerEntryRepository;
    @Mock
    private SupplierLedgerEntryRepository supplierLedgerEntryRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private SupplierPaymentRepository supplierPaymentRepository;

    @InjectMocks
    private ControlAccountReconciliationService reconciliationService;

    private static JournalLine line(String accountCode, BigDecimal debit, BigDecimal credit) {
        return JournalLine.builder().id(accountCode + debit + credit).accountCode(accountCode)
                .debitAmountInr(debit).creditAmountInr(credit).build();
    }

    private void seedConsistentFixture() {
        // 1200 Accounts Receivable (ASSET): an invoice raised (debit 1000) then partly paid (credit 400) -> GL 600.
        when(ledgerAccountRepository.findByCode("1200"))
                .thenReturn(Optional.of(LedgerAccount.builder().code("1200").accountType(LedgerAccountType.ASSET).build()));
        when(journalLineRepository.findByAccountCode("1200")).thenReturn(List.of(
                line("1200", new BigDecimal("1000.00"), BigDecimal.ZERO),
                line("1200", BigDecimal.ZERO, new BigDecimal("400.00"))));
        when(customerLedgerEntryRepository.findAll()).thenReturn(List.of(
                CustomerLedgerEntry.builder().id("C1").debitAmountInr(new BigDecimal("1000.00")).creditAmountInr(BigDecimal.ZERO).build(),
                CustomerLedgerEntry.builder().id("C2").debitAmountInr(BigDecimal.ZERO).creditAmountInr(new BigDecimal("400.00")).build()));

        // 2200 Accounts Payable (LIABILITY): a bill booked (credit 500) then partly paid (debit 200) -> GL 300.
        when(ledgerAccountRepository.findByCode("2200"))
                .thenReturn(Optional.of(LedgerAccount.builder().code("2200").accountType(LedgerAccountType.LIABILITY).build()));
        when(journalLineRepository.findByAccountCode("2200")).thenReturn(List.of(
                line("2200", BigDecimal.ZERO, new BigDecimal("500.00")),
                line("2200", new BigDecimal("200.00"), BigDecimal.ZERO)));
        when(supplierLedgerEntryRepository.findAll()).thenReturn(List.of(
                SupplierLedgerEntry.builder().id("S1").creditAmountInr(new BigDecimal("500.00")).debitAmountInr(BigDecimal.ZERO).build(),
                SupplierLedgerEntry.builder().id("S2").creditAmountInr(BigDecimal.ZERO).debitAmountInr(new BigDecimal("200.00")).build()));

        // 2110 Client Advances Held (LIABILITY): a 50000 deposit, 20000 of it applied to an invoice -> GL 30000.
        when(ledgerAccountRepository.findByCode("2110"))
                .thenReturn(Optional.of(LedgerAccount.builder().code("2110").accountType(LedgerAccountType.LIABILITY).build()));
        when(journalLineRepository.findByAccountCode("2110")).thenReturn(List.of(
                line("2110", BigDecimal.ZERO, new BigDecimal("50000.00")),
                line("2110", new BigDecimal("20000.00"), BigDecimal.ZERO)));
        when(paymentReceiptRepository.findAll()).thenReturn(List.of(
                PaymentReceipt.builder().id("R1").isAdvance(true).invoiceId(null).reversedAt(null)
                        .amountInr(new BigDecimal("50000.00")).build(),
                PaymentReceipt.builder().id("R2").appliedFromAdvance(true).reversedAt(null)
                        .amountInr(new BigDecimal("20000.00")).build()));

        // 1300 Supplier Advances (ASSET): a 10000 advance paid, 4000 of it applied to a bill -> GL 6000.
        when(ledgerAccountRepository.findByCode("1300"))
                .thenReturn(Optional.of(LedgerAccount.builder().code("1300").accountType(LedgerAccountType.ASSET).build()));
        when(journalLineRepository.findByAccountCode("1300")).thenReturn(List.of(
                line("1300", new BigDecimal("10000.00"), BigDecimal.ZERO),
                line("1300", BigDecimal.ZERO, new BigDecimal("4000.00"))));
        when(supplierPaymentRepository.findAll()).thenReturn(List.of(
                SupplierPayment.builder().id("P1").isAdvance(true).reversedAt(null)
                        .amountInr(new BigDecimal("10000.00")).build(),
                SupplierPayment.builder().id("P2").appliedFromAdvance(true).reversedAt(null)
                        .amountInr(new BigDecimal("4000.00")).build()));
    }

    @Test
    void reconciliationNetsToZeroVarianceAcrossAllFourControlAccountsWhenGlAndSubsidiaryAgree() {
        seedConsistentFixture();

        List<ControlAccountReconciliationRowResponse> rows = reconciliationService.reconcile();

        assertThat(rows).hasSize(4);
        Function<String, ControlAccountReconciliationRowResponse> byCode =
                code -> rows.stream().filter(r -> r.getAccountCode().equals(code)).findFirst().orElseThrow();

        assertThat(byCode.apply("1200").getGlBalanceInr()).isEqualByComparingTo("600.00");
        assertThat(byCode.apply("1200").getSubsidiaryTotalInr()).isEqualByComparingTo("600.00");

        assertThat(byCode.apply("2200").getGlBalanceInr()).isEqualByComparingTo("300.00");
        assertThat(byCode.apply("2200").getSubsidiaryTotalInr()).isEqualByComparingTo("300.00");

        assertThat(byCode.apply("2110").getGlBalanceInr()).isEqualByComparingTo("30000.00");
        assertThat(byCode.apply("2110").getSubsidiaryTotalInr()).isEqualByComparingTo("30000.00");

        assertThat(byCode.apply("1300").getGlBalanceInr()).isEqualByComparingTo("6000.00");
        assertThat(byCode.apply("1300").getSubsidiaryTotalInr()).isEqualByComparingTo("6000.00");

        for (ControlAccountReconciliationRowResponse row : rows) {
            assertThat(row.getVarianceInr())
                    .as("variance on " + row.getAccountCode() + " (" + row.getAccountName() + ")")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void reconciliationSurfacesANonZeroVarianceWhenTheGlAndSubsidiaryLedgerDisagree() {
        seedConsistentFixture();
        // Perturb only the GL side of Accounts Receivable - an extra, unexplained 50.00 debit with
        // no matching subsidiary-ledger entry, simulating exactly the failure this gate exists to catch.
        when(journalLineRepository.findByAccountCode("1200")).thenReturn(List.of(
                line("1200", new BigDecimal("1000.00"), BigDecimal.ZERO),
                line("1200", BigDecimal.ZERO, new BigDecimal("400.00")),
                line("1200", new BigDecimal("50.00"), BigDecimal.ZERO)));

        List<ControlAccountReconciliationRowResponse> rows = reconciliationService.reconcile();

        ControlAccountReconciliationRowResponse arRow = rows.stream()
                .filter(r -> r.getAccountCode().equals("1200")).findFirst().orElseThrow();
        assertThat(arRow.getGlBalanceInr()).isEqualByComparingTo("650.00");
        assertThat(arRow.getSubsidiaryTotalInr()).isEqualByComparingTo("600.00");
        assertThat(arRow.getVarianceInr()).isEqualByComparingTo("50.00");

        // The other three control accounts are untouched and must still net to zero.
        rows.stream().filter(r -> !r.getAccountCode().equals("1200"))
                .forEach(r -> assertThat(r.getVarianceInr())
                        .as("variance on " + r.getAccountCode()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    /**
     * Regression for a bug found by posting a real advance payment against a live QA tenant:
     * {@code accountsPayableSubsidiaryTotal} summed every SupplierLedgerEntry indiscriminately,
     * so an ADVANCE_PAID row (which belongs entirely to account 1300, already counted by
     * {@code supplierAdvancesSubsidiaryTotal}) bled into account 2200's subsidiary total too,
     * understating what is actually owed on bills and making 2200 permanently fail to reconcile
     * the moment any vendor had an advance on file.
     */
    @Test
    void anAdvancePaidSupplierLedgerEntryIsExcludedFromTheAccountsPayableSubsidiaryTotal() {
        seedConsistentFixture();
        // On top of the consistent 2200 fixture (a bill booked, credit 500; partly paid, debit 200
        // -> subsidiary 300.00), add an unrelated 5000.00 advance to a different vendor. The GL
        // side for 2200 is untouched - an advance posts to 1300, never 2200 - so if the subsidiary
        // total also stays at 300.00, the exclusion is working.
        when(supplierLedgerEntryRepository.findAll()).thenReturn(List.of(
                SupplierLedgerEntry.builder().id("S1").entryType(SupplierLedgerEntryType.BILL_BOOKED)
                        .creditAmountInr(new BigDecimal("500.00")).debitAmountInr(BigDecimal.ZERO).build(),
                SupplierLedgerEntry.builder().id("S2").entryType(SupplierLedgerEntryType.PAYMENT_MADE)
                        .creditAmountInr(BigDecimal.ZERO).debitAmountInr(new BigDecimal("200.00")).build(),
                SupplierLedgerEntry.builder().id("S3").entryType(SupplierLedgerEntryType.ADVANCE_PAID)
                        .creditAmountInr(BigDecimal.ZERO).debitAmountInr(new BigDecimal("5000.00")).build()));

        List<ControlAccountReconciliationRowResponse> rows = reconciliationService.reconcile();

        ControlAccountReconciliationRowResponse apRow = rows.stream()
                .filter(r -> r.getAccountCode().equals("2200")).findFirst().orElseThrow();
        assertThat(apRow.getGlBalanceInr()).isEqualByComparingTo("300.00");
        assertThat(apRow.getSubsidiaryTotalInr())
                .as("the 5000.00 advance must not bleed into the bill-only AP total")
                .isEqualByComparingTo("300.00");
        assertThat(apRow.getVarianceInr()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
