package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceIssueRequest;
import com.voyra.crm.dto.PurchaseEntryRequest;
import com.voyra.crm.dto.VoucherChargesRequest;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.enums.MarkupMode;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Purchase ?": the client's invoice is generated with the supplier bills of the vouchers it bills. */
@ExtendWith(MockitoExtension.class)
class InvoicePurchaseServiceTest {

    @Mock private InvoiceDocumentService invoiceDocumentService;
    @Mock private InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock private BookingDocumentRepository bookingDocumentRepository;
    @Mock private BookingDocumentService bookingDocumentService;
    @Mock private SupplierInvoiceService supplierInvoiceService;
    @InjectMocks private InvoicePurchaseService service;

    private final BookingDocument ticket = BookingDocument.builder().id("V1").bookingId("B1").referenceNumber("LVBJAY")
            .supplierInvoiceId("SI1").charges(Map.of("basic", new BigDecimal("8200"))).build();

    private static InvoiceIssueRequest purchase(String voucherId, String commission) {
        PurchaseEntryRequest p = new PurchaseEntryRequest();
        p.setBookingDocumentId(voucherId);
        p.setSupplierInvoiceNumber(" IND/48213 ");
        p.setInvoiceDate(LocalDate.of(2026, 10, 20));
        if (commission != null) {
            p.setCommissionMode(MarkupMode.PERCENT);
            p.setCommissionValue(new BigDecimal(commission));
            p.setSupplierTdsRate(new BigDecimal("2"));
        }
        InvoiceIssueRequest r = new InvoiceIssueRequest();
        r.setPurchases(List.of(p));
        return r;
    }

    private void onTheInvoice() {
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().bookingDocumentId("V1").build()));
    }

    @Test
    void theInvoiceIsGeneratedThenTheTickedVouchersCommissionAndSupplierBillAreBooked() {
        onTheInvoice();
        when(bookingDocumentRepository.findById("V1")).thenReturn(Optional.of(ticket));

        service.issue("I1", purchase("V1", "5"));

        ArgumentCaptor<VoucherChargesRequest> commission = ArgumentCaptor.forClass(VoucherChargesRequest.class);
        // The invoice first, so a commission-agent bill finds it raised and clears the client's pass-through.
        InOrder order = Mockito.inOrder(bookingDocumentService, supplierInvoiceService, invoiceDocumentService);
        order.verify(invoiceDocumentService).issue("I1");
        order.verify(bookingDocumentService).setCharges(Mockito.eq("B1"), Mockito.eq("V1"), commission.capture());
        order.verify(supplierInvoiceService).confirmPurchase("SI1", " IND/48213 ", LocalDate.of(2026, 10, 20));
        // The accountant's breakdown stays as it is; only the commission comes with the purchase.
        assertThat(commission.getValue().getCharges()).isEqualTo(ticket.getCharges());
        assertThat(commission.getValue().getCommissionValue()).isEqualByComparingTo("5");
    }

    @Test
    void noPurchasesIsAPlainGenerateAndNoCommissionKeepsTheVouchers() {
        service.issue("I1", null);
        verify(invoiceDocumentService).issue("I1");

        onTheInvoice();
        when(bookingDocumentRepository.findById("V1")).thenReturn(Optional.of(ticket));
        service.issue("I1", purchase("V1", null));
        verify(bookingDocumentService, never()).setCharges(any(), any(), any());
        verify(supplierInvoiceService).confirmPurchase(Mockito.eq("SI1"), any(), any());
    }

    @Test
    void aVoucherNotOnTheInvoiceOrWithNoSupplierBillIsRefusedAndNothingIsGenerated() {
        onTheInvoice();
        assertThatThrownBy(() -> service.issue("I1", purchase("V9", null))).hasMessage("That voucher isn't on this invoice");

        BookingDocument noBill = ticket.toBuilder().supplierInvoiceId(null).build();
        when(bookingDocumentRepository.findById("V1")).thenReturn(Optional.of(noBill));
        assertThatThrownBy(() -> service.issue("I1", purchase("V1", null)))
                .hasMessage("PNR LVBJAY has no supplier bill - it needs a supplier and a cost");
        verify(invoiceDocumentService, never()).issue(any());
    }
}
