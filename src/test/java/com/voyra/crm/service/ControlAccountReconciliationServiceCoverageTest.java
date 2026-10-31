package com.voyra.crm.service;

import com.voyra.crm.dto.ControlAccountReconciliationRowResponse;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SystemAccount;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlAccountReconciliationServiceCoverageTest {

    @Mock LedgerAccountRepository ledgerAccountRepository;
    @Mock JournalLineRepository journalLineRepository;
    @Mock CustomerLedgerEntryRepository customerLedgerEntryRepository;
    @Mock SupplierLedgerEntryRepository supplierLedgerEntryRepository;
    @Mock PaymentReceiptRepository paymentReceiptRepository;
    @Mock SupplierPaymentRepository supplierPaymentRepository;
    @InjectMocks ControlAccountReconciliationService service;

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void thePoolsCountOnlyLiveDepositsLessRefundsAndDrawdownsWithAMissingControlAccountReadingZero() {
        when(paymentReceiptRepository.findAll()).thenReturn(List.of(
                PaymentReceipt.builder().isAdvance(true).direction(ReceiptDirection.RECEIPT).amountInr(d("1000")).build(),
                PaymentReceipt.builder().isAdvance(true).direction(ReceiptDirection.REFUND).amountInr(d("200")).build(),
                PaymentReceipt.builder().isAdvance(true).invoiceId("I1").amountInr(d("999")).build(),
                PaymentReceipt.builder().isAdvance(false).amountInr(d("999")).build(),
                PaymentReceipt.builder().isAdvance(true).reversedAt(LocalDateTime.now()).amountInr(d("999")).build(),
                PaymentReceipt.builder().isAdvance(true).reversesReceiptId("R0").amountInr(d("-999")).build(),
                PaymentReceipt.builder().appliedFromAdvance(true).invoiceId("I2").amountInr(d("300")).build(),
                PaymentReceipt.builder().appliedFromAdvance(true).invoiceId("I3").reversedAt(LocalDateTime.now()).amountInr(d("50")).build()));
        when(supplierPaymentRepository.findAll()).thenReturn(List.of(
                SupplierPayment.builder().isAdvance(true).amountInr(d("700")).build(),
                SupplierPayment.builder().isAdvance(true).reversedAt(LocalDateTime.now()).amountInr(d("90")).build(),
                SupplierPayment.builder().appliedFromAdvance(true).amountInr(d("150")).build(),
                SupplierPayment.builder().appliedFromAdvance(true).reversesPaymentId("P0").amountInr(d("-80")).build(),
                SupplierPayment.builder().isAdvance(false).appliedFromAdvance(false).amountInr(d("999")).build()));

        Map<String, ControlAccountReconciliationRowResponse> rows = service.reconcile().stream()
                .collect(Collectors.toMap(ControlAccountReconciliationRowResponse::getAccountCode, r -> r));

        assertThat(rows.get(SystemAccount.CLIENT_ADVANCES_HELD.code()).getSubsidiaryTotalInr()).isEqualByComparingTo("500");
        assertThat(rows.get(SystemAccount.ACCOUNTS_RECEIVABLE.code()).getSubsidiaryTotalInr()).isEqualByComparingTo("500");
        assertThat(rows.get(SystemAccount.ACCOUNTS_PAYABLE.code()).getSubsidiaryTotalInr()).isEqualByComparingTo("-150");
        assertThat(rows.get(SystemAccount.SUPPLIER_ADVANCES.code()).getSubsidiaryTotalInr()).isEqualByComparingTo("550");
        assertThat(rows.get(SystemAccount.SUPPLIER_ADVANCES.code()).getGlBalanceInr()).isEqualByComparingTo("0");
        assertThat(rows.get(SystemAccount.SUPPLIER_ADVANCES.code()).getVarianceInr()).isEqualByComparingTo("-550");
        verifyNoInteractions(journalLineRepository);
    }
}
