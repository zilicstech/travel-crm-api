package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.InvoiceTaxRequest;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
import com.voyra.crm.repository.TaxRateConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The invoices a booking raises by itself: the one-click Generate invoice and, on a cancellation
 * before any invoice, the cancellation-charges invoice. Both carry the agency's default GST for what
 * the booking is, the same slab a hand-raised invoice would pick; on a commission-agent (air) invoice
 * it is charged on the agency's fee only (InvoiceDocumentService#resolveTaxableBase).
 */
@Service
@RequiredArgsConstructor
public class BookingInvoiceIssuer {

    private final InvoiceDocumentService invoiceDocumentService;
    private final TaxRateConfigRepository taxRateConfigRepository;

    /** What the booking is, for picking its GST slab. */
    static SupplyNature supplyNature(InvoiceServiceCategory category, boolean international) {
        return switch (category) {
            case AIR_DOMESTIC, AIR_INTERNATIONAL -> SupplyNature.AIR_TICKET;
            case HOTEL -> SupplyNature.HOTEL_ONLY;
            case VISA -> SupplyNature.VISA_SERVICE;
            case PACKAGE -> international ? SupplyNature.OVERSEAS_PACKAGE : SupplyNature.DOMESTIC_PACKAGE;
            default -> SupplyNature.OTHER;
        };
    }

    /** Sets the booking's supply nature and its default GST on a draft; none configured means no tax. */
    public void applyDefaultGst(InvoiceDraftRequest draft, Booking booking) {
        boolean international = Boolean.TRUE.equals(booking.getInternationalTrip());
        InvoiceServiceCategory category = draft.getServiceCategory() != null
                ? draft.getServiceCategory() : InvoiceServiceCategory.forBooking(booking.getType(), international);
        SupplyNature nature = supplyNature(category, international);
        draft.setSupplyNature(nature);
        taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, nature)
                .ifPresent(gst -> {
                    InvoiceTaxRequest tax = new InvoiceTaxRequest();
                    tax.setLabel(gst.getLabel());
                    tax.setTaxRateConfigId(gst.getId());
                    tax.setMode(TaxLineMode.PERCENT);
                    tax.setRatePercent(gst.getRatePercent());
                    tax.setVisibleToCustomer(true);
                    draft.setTaxes(List.of(tax));
                });
    }

    /**
     * What {@link #issueCancellationCharges} will bill, without writing anything - the cancel preview
     * shows the same refund the cancel then gives. Same base and rounding as the invoice's own tax:
     * the agency fee alone on a commission-agent (air) booking, everything kept otherwise.
     */
    public BigDecimal cancellationChargesTotal(Booking booking, BigDecimal supplierCharge, BigDecimal agencyFee) {
        BigDecimal kept = supplierCharge.add(agencyFee);
        boolean international = Boolean.TRUE.equals(booking.getInternationalTrip());
        InvoiceServiceCategory category = InvoiceServiceCategory.forBooking(booking.getType(), international);
        BigDecimal base = category.defaultBillingModel() == InvoiceBillingModel.COMMISSION_AGENT ? agencyFee : kept;
        BigDecimal gst = taxRateConfigRepository
                .findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(TaxKind.GST, supplyNature(category, international))
                .map(g -> base.multiply(g.getRatePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
        return kept.add(gst);
    }

    /**
     * A booking cancelled before it was invoiced still owes what the supplier and the agency kept:
     * this issues a tax invoice for it. Any advance held for the booking is applied on issue (the
     * usual issue path), so the refund is what is left. On a commission-agent booking the supplier's
     * share is the pass-through and only the agency fee is income.
     */
    public InvoiceResponse issueCancellationCharges(Booking booking, BigDecimal supplierCharge, BigDecimal agencyFee) {
        BigDecimal kept = supplierCharge.add(agencyFee);
        InvoiceDraftRequest draft = new InvoiceDraftRequest();
        draft.setBookingId(booking.getId());
        draft.setPassThroughAmount(supplierCharge);
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setDescription("Cancellation charges - " + BookingService.bookingRef(booking.getBookingNumber()));
        line.setServiceType(booking.getServiceType());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(kept);
        draft.setLines(List.of(line));
        applyDefaultGst(draft, booking);
        String id = invoiceDocumentService.createDraft(draft).getId();
        return invoiceDocumentService.issue(id);
    }
}
