package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceIssueRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.PurchaseEntryRequest;
import com.voyra.crm.dto.VoucherChargesRequest;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generating a client invoice together with the purchases it bills - ePrompt's "Purchase ?" tick: each ticked
 * voucher's supplier bill gets the supplier's bill number (and the commission, on a flight) and is booked. All in
 * one transaction, so there is never an invoice without the purchases it was generated with, or the reverse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePurchaseService {

    private final InvoiceDocumentService invoiceDocumentService;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingDocumentService bookingDocumentService;
    private final SupplierInvoiceService supplierInvoiceService;

    @Transactional
    public InvoiceResponse issue(String invoiceId, InvoiceIssueRequest request) {
        List<PurchaseEntryRequest> purchases = request == null || request.getPurchases() == null ? List.of() : request.getPurchases();
        Set<String> onInvoice = purchases.isEmpty() ? Set.of() : invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId).stream()
                .map(InvoiceLineItem::getBookingDocumentId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<BookingDocument> vouchers = purchases.stream().map(p -> {
            if (!onInvoice.contains(p.getBookingDocumentId())) {
                throw new IllegalArgumentException("That voucher isn't on this invoice");
            }
            BookingDocument voucher = bookingDocumentRepository.findById(p.getBookingDocumentId())
                    .orElseThrow(() -> new IllegalArgumentException("Voucher not found: " + p.getBookingDocumentId()));
            if (voucher.getSupplierInvoiceId() == null) {
                throw new IllegalStateException(label(voucher) + " has no supplier bill - it needs a supplier and a cost");
            }
            return voucher;
        }).toList();
        // The invoice first: a commission-agent bill clears the client's pass-through (2210) only once the
        // booking's invoice is raised, so the bills are booked after it - still in this one transaction.
        InvoiceResponse issued = invoiceDocumentService.issue(invoiceId);
        for (int i = 0; i < purchases.size(); i++) {
            PurchaseEntryRequest p = purchases.get(i);
            BookingDocument voucher = vouchers.get(i);
            if (p.getCommissionValue() != null && commissionChanged(voucher, p)) {
                VoucherChargesRequest commission = new VoucherChargesRequest();
                commission.setCharges(voucher.getCharges());
                commission.setCommissionMode(p.getCommissionMode());
                commission.setCommissionValue(p.getCommissionValue());
                commission.setSupplierTdsRate(p.getSupplierTdsRate());
                bookingDocumentService.setCharges(voucher.getBookingId(), voucher.getId(), commission);
            }
            supplierInvoiceService.confirmPurchase(voucher.getSupplierInvoiceId(), p.getSupplierInvoiceNumber(), p.getInvoiceDate());
        }
        log.info("Invoice generated with purchases: invoiceId={}, purchases={}", invoiceId, purchases.size());
        return issued;
    }

    /** Only a changed commission is entered again - the accountant's breakdown it rides with stays as it is. */
    private static boolean commissionChanged(BookingDocument v, PurchaseEntryRequest p) {
        java.math.BigDecimal value = p.getCommissionValue();
        java.math.BigDecimal before = v.getCommissionValue() != null ? v.getCommissionValue() : java.math.BigDecimal.ZERO;
        if (value.compareTo(before) != 0) return true;
        return value.signum() > 0 && (p.getCommissionMode() != v.getCommissionMode()
                || !java.util.Objects.equals(p.getSupplierTdsRate() == null ? null : p.getSupplierTdsRate().stripTrailingZeros(),
                        v.getSupplierTdsRate() == null ? null : v.getSupplierTdsRate().stripTrailingZeros()));
    }

    private static String label(BookingDocument v) {
        return v.getReferenceNumber() != null ? "PNR " + v.getReferenceNumber() : "This voucher";
    }
}
