package com.voyra.crm.service;

import com.voyra.crm.dto.ApAgeingRowResponse;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierLedgerEntry;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.VendorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BUG-CRX-26: AP ageing ages only the bills still owed, so on its own it disagreed with the vendor
 * statement, which also nets supplier refunds due and unapplied advances. Each row now carries
 * those credits and the net, which equals the statement balance.
 */
@ExtendWith(MockitoExtension.class)
class SupplierLedgerAgeingTest {

    @Mock private SupplierLedgerEntryRepository ledgerRepository;
    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private JournalService journalService;
    @InjectMocks private SupplierLedgerService supplierLedgerService;

    private static SupplierLedgerEntry entry(String credit, String debit) {
        return SupplierLedgerEntry.builder().vendorId("AG").creditAmountInr(new BigDecimal(credit)).debitAmountInr(new BigDecimal(debit)).build();
    }

    @Test
    void apAgeingNetsRefundsDueAndAdvancesToTheVendorStatementBalance() {
        SupplierInvoice open = SupplierInvoice.builder().id("B1").vendorId("AG").vendorName("Agoda")
                .status(SupplierInvoiceStatus.APPROVED).dueDate(LocalDate.now().minusDays(10))
                .balanceDueInr(new BigDecimal("45000.00")).build();
        SupplierInvoice refundDue = SupplierInvoice.builder().id("B2").vendorId("AG").vendorName("Agoda")
                .status(SupplierInvoiceStatus.PAID).balanceDueInr(new BigDecimal("-12000.00")).build();
        when(supplierInvoiceRepository.findAll()).thenReturn(List.of(open, refundDue));
        // Statement: bills 57,000 booked, 12,000 credited back by the supplier, 6,500 advance paid -> 26,500.
        when(ledgerRepository.findAll()).thenReturn(List.of(entry("57000.00", "0"), entry("0", "24000.00"), entry("0", "6500.00")));
        when(vendorRepository.findAll()).thenReturn(List.of(Vendor.builder().id("AG").name("Agoda").build()));

        List<ApAgeingRowResponse> rows = supplierLedgerService.outstanding();

        assertThat(rows).hasSize(1);
        ApAgeingRowResponse agoda = rows.get(0);
        assertThat(agoda.getDays1To30()).isEqualByComparingTo("45000.00");
        assertThat(agoda.getTotalPayableInr()).isEqualByComparingTo("45000.00");
        assertThat(agoda.getCreditsInr()).isEqualByComparingTo("-18500.00");
        assertThat(agoda.getNetBalanceInr()).isEqualByComparingTo("26500.00");
    }
}
