package com.voyra.crm.service;

import com.voyra.crm.dto.BookingDocumentCreateRequest;
import com.voyra.crm.dto.BookingDocumentResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Voucher documents attached to a booking - an e-ticket, a hotel voucher, an insurance
 * certificate - several per booking, each with its own reference and file. Mirrors
 * the retired LeadVoucherService's discipline exactly: {@code addDocument} is transactional;
 * {@code attachFile}/{@code downloadFile}/{@code deleteDocument} are not, because storing or
 * retrieving a file is object storage work blueprint §8.6 forbids inside a transaction.
 * {@code fileKey} is opaque and never leaves this service.

 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingDocumentService {

    private static final String DOCUMENT_FILE_CATEGORY = "booking-documents";

    private final BookingDocumentRepository bookingDocumentRepository;
    private final BookingService bookingService;
    private final LeadTimelineService leadTimelineService;
    private final FileStorageService fileStorageService;
    private final BaseCurrency baseCurrency;
    private final InvoiceLineSource invoiceLineSource;
    private final com.voyra.crm.repository.MemberRepository memberRepository;

    @Transactional
    public BookingDocumentResponse addDocument(String bookingId, BookingDocumentCreateRequest request) {
        Booking booking = bookingService.lockAccessibleBooking(bookingId);
        String base = baseCurrency.code();
        com.voyra.crm.util.FxMath.Pricing pricing = com.voyra.crm.util.FxMath.resolve(request, base);
        if (booking.getBookingStatus() == com.voyra.crm.enums.BookingStatus.CANCELLED
                && (nz(pricing.netCost()).signum() > 0 || request.getMarkupMode() != null)) {
            throw new IllegalStateException("This booking is cancelled - a voucher can't add cost or price to it");
        }

        BookingDocument document = BookingDocument.builder()
                .id(UniqueIdResolver.resolve(bookingDocumentRepository::existsById))
                .bookingId(bookingId)
                .docType(request.getDocType())
                .referenceNumber(request.getReferenceNumber())
                .issuedDate(request.getIssuedDate())
                .notes(request.getNotes())
                .netCost(nz(pricing.netCost()))
                .details(com.voyra.crm.util.VoucherDetails.clean(booking.getType(), request.getDetails()))
                .markupMode(request.getMarkupMode())
                .markupValue(pricing.markupValue())
                .createdAt(LocalDateTime.now())
                .createdBy(currentUserId())
                .build();
        applyTrip(booking, document, request);
        applyTripType(booking, request.getInternationalTrip());
        document.keepForeign(pricing, base);
        bookingService.applyVoucher(booking, document, request.getVendorId(), Boolean.TRUE.equals(request.getOverrideCreditLimit()));
        bookingDocumentRepository.save(document);
        bookingService.syncReference(booking, null);

        if (booking.getLeadId() != null) {
            leadTimelineService.record(booking.getLeadId(), booking.getServiceId(),
                    LeadTimelineEventType.BOOKING_DOCUMENT_ADDED,
                    request.getDocType() + " added to " + booking.getDestination() + " booking");
        }
        log.info("Booking document added: bookingId={}, documentId={}", bookingId, document.getId());
        return toResponse(document);
    }

    /** Text fields change in place; a changed cost, markup or supplier takes the old money back and books the new. */
    @Transactional
    public BookingDocumentResponse updateDocument(String bookingId, String documentId, BookingDocumentCreateRequest request) {
        Booking booking = bookingService.lockAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        bookingService.assertNotInvoiced(document);
        String oldReference = document.getReferenceNumber();
        document.setDocType(request.getDocType());
        document.setDetails(com.voyra.crm.util.VoucherDetails.clean(booking.getType(), request.getDetails()));
        applyTrip(booking, document, request);
        applyTripType(booking, request.getInternationalTrip());
        document.setReferenceNumber(request.getReferenceNumber());
        document.setIssuedDate(request.getIssuedDate());
        document.setNotes(request.getNotes());
        String vendorId = request.getVendorId() != null && !request.getVendorId().isBlank() ? request.getVendorId() : booking.getVendorId();
        String base = baseCurrency.code();
        com.voyra.crm.util.FxMath.Pricing pricing = com.voyra.crm.util.FxMath.resolve(request, base);
        // The edited figures on a scratch voucher: what the commission and the bill would become. The accountant's
        // breakdown and commission stay while the purchase price does; a new price clears them, to be entered again.
        BookingDocument priced = BookingDocument.builder().netCost(nz(pricing.netCost())).build();
        priced.keepForeign(pricing, base);
        if (java.util.Objects.equals(priced.getPurchaseCurrency(), document.getPurchaseCurrency()) && gross(priced).compareTo(gross(document)) == 0) {
            priced.setCharges(document.getCharges());
            BookingService.applyCommission(priced, document.getCommissionMode(), document.getCommissionValue(), document.getSupplierTdsRate());
        }
        boolean moneyChanged = nz(pricing.netCost()).compareTo(nz(document.getNetCost())) != 0
                || nz(priced.getCommission()).compareTo(nz(document.getCommission())) != 0
                || nz(priced.getSupplierTds()).compareTo(nz(document.getSupplierTds())) != 0
                || request.getMarkupMode() != document.getMarkupMode()
                || nz(pricing.markupValue()).compareTo(nz(document.getMarkupValue())) != 0
                || !java.util.Objects.equals(vendorId, document.getVendorId())
                // Same base cost, other currency or rate: the supplier bill is in that currency, so it moves too.
                || !java.util.Objects.equals(priced.getPurchaseCurrency(), document.getPurchaseCurrency())
                || nz(priced.getPurchaseRate()).compareTo(nz(document.getPurchaseRate())) != 0;
        if (moneyChanged) {
            if (booking.getBookingStatus() == com.voyra.crm.enums.BookingStatus.CANCELLED) {
                throw new IllegalStateException("This booking is cancelled - a voucher's cost or price can't change");
            }
            bookingService.reverseVoucher(booking, document, false);
            document.setSupplierInvoiceId(null);
            document.setNetCost(nz(pricing.netCost()));
            document.setMarkupMode(request.getMarkupMode());
            document.setMarkupValue(pricing.markupValue());
            document.keepForeign(pricing, base);
            copyCommission(priced, document);
            bookingService.applyVoucher(booking, document, request.getVendorId(), Boolean.TRUE.equals(request.getOverrideCreditLimit()));
            if (booking.getSellingPrice().compareTo(bookingService.invoicedPrice(bookingId)) < 0) {
                throw new IllegalStateException("This voucher's price is already invoiced - it can't go below that");
            }
        }
        document.setCharges(priced.getCharges());
        copyCommission(priced, document);
        bookingDocumentRepository.save(document);
        bookingService.syncReference(booking, oldReference);
        log.info("Booking document edited: bookingId={}, documentId={}, moneyChanged={}", bookingId, documentId, moneyChanged);
        return toResponse(document);
    }

    /**
     * The accountant's fare breakdown of a voucher and its supplier's commission. The breakdown must add up to the
     * purchase price the agent entered, so the voucher's money stays as it is; it may change while the voucher waits
     * on a draft invoice (which picks it up) but not once invoiced. The commission moves the booking's profit and
     * the voucher's still-unapproved supplier bill.
     */
    @Transactional
    public BookingDocumentResponse setCharges(String bookingId, String documentId, com.voyra.crm.dto.VoucherChargesRequest request) {
        Booking booking = bookingService.lockAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        java.util.Map<String, java.math.BigDecimal> charges = com.voyra.crm.util.VoucherCharges.clean(request.getCharges());
        // Its commission is the supplier bill's business and may still change; the breakdown is printed on the invoice.
        if (bookingService.invoiced(bookingId).covers(document) && !com.voyra.crm.util.VoucherCharges.same(charges, document.getCharges())) {
            throw new IllegalStateException("This voucher is already invoiced - its fare breakdown is on the invoice");
        }
        java.math.BigDecimal total = com.voyra.crm.util.VoucherCharges.total(charges);
        if (charges != null && total.compareTo(gross(document)) != 0) {
            throw new IllegalArgumentException("The breakdown adds up to " + total.toPlainString()
                    + "; the voucher's purchase price is " + gross(document).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        }
        BookingDocument priced = BookingDocument.builder().netCost(document.getNetCost()).purchasePrice(document.getPurchasePrice())
                .purchaseRate(document.getPurchaseRate()).charges(charges).build();
        BookingService.applyCommission(priced, request.getCommissionMode(), request.getCommissionValue(), request.getSupplierTdsRate());
        bookingService.changeCommission(booking, document, priced);
        document.setCharges(charges);
        copyCommission(priced, document);
        bookingDocumentRepository.save(document);
        invoiceLineSource.refreshDraftCharges(document);
        log.info("Voucher fare breakdown set: bookingId={}, documentId={}, commission={}", bookingId, documentId, document.getCommission());
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<BookingDocumentResponse> listForBooking(String bookingId) {
        bookingService.findAccessibleBooking(bookingId);
        InvoiceLineSource.Invoiced invoiced = bookingService.invoiced(bookingId);
        java.util.Set<String> onDraft = bookingService.draftVoucherIds(bookingId);
        List<BookingDocument> docs = bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc(bookingId);
        java.util.Set<String> approvedBills = bookingService.approvedBillIds(docs);
        return docs.stream().map(d -> BookingService.toDocumentResponse(d, invoiced, onDraft, approvedBills)).toList();
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public void deleteDocument(String bookingId, String documentId) {
        // Soft delete: the row is marked and the stored file is kept.
        bookingService.removeVoucher(bookingId, documentId);
        log.info("Booking document removed: bookingId={}, documentId={}", bookingId, documentId);
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public BookingDocumentResponse attachFile(String bookingId, String documentId, MultipartFile file) {
        com.voyra.crm.util.PlatformManaged.check(bookingService.findAccessibleBooking(bookingId).getPlatformBookingId() != null);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        String fileKey = fileStorageService.store(tenantId, DOCUMENT_FILE_CATEGORY, documentId, file);

        document.setFileKey(fileKey);
        document.setFileName(file.getOriginalFilename());
        document.setContentType(file.getContentType());
        bookingDocumentRepository.save(document);
        log.info("Booking document file attached: bookingId={}, documentId={}", bookingId, documentId);
        return toResponse(document);
    }

    /** Not {@code @Transactional} - see class javadoc. */
    public BookingDocumentFileContent downloadFile(String bookingId, String documentId) {
        // Anyone who can see the booking can open its uploaded voucher, paid or not.
        bookingService.findAccessibleBooking(bookingId);
        BookingDocument document = bookingDocumentRepository.findByIdAndBookingId(documentId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
        if (document.getFileKey() == null) {
            throw new IllegalArgumentException("No file attached to this document");
        }
        Resource resource = fileStorageService.retrieve(document.getFileKey());
        return new BookingDocumentFileContent(resource, document.getFileName(), document.getContentType());
    }

    /** Not a DTO - never serialized to JSON, only unpacked into a binary response by the controller. */
    public record BookingDocumentFileContent(Resource resource, String filename, String contentType) {
    }

    private String currentUserId() {
        return SecurityContextUtil.getCurrentUserOrThrow().userId();
    }

    private BookingDocumentResponse toResponse(BookingDocument d) {
        return BookingService.toDocumentResponse(d, bookingService.invoiced(d.getBookingId()),
                bookingService.draftVoucherIds(d.getBookingId()), bookingService.approvedBillIds(List.of(d)));
    }

    /** What the supplier charges, in the purchase currency - the figure the breakdown adds up to. */
    /**
     * An air ticket names its travellers - the client's own members, snapshotted as the ticket names them -
     * and its flights, from which its flat flight details are worked out. Any other voucher carries neither.
     */
    private void applyTrip(Booking booking, BookingDocument document, BookingDocumentCreateRequest request) {
        if (booking.getType() != com.voyra.crm.enums.BookingType.FLIGHT
                || request.getDocType() != com.voyra.crm.enums.BookingDocumentType.E_TICKET) {
            document.setTravellers(null);
            document.setLegs(null);
            return;
        }
        List<com.voyra.crm.dto.VoucherLeg> legs = com.voyra.crm.util.VoucherTrip.cleanLegs(request.getLegs());
        List<String> ids = request.getTravellers() == null ? List.of() : request.getTravellers().stream()
                .map(com.voyra.crm.dto.VoucherTraveller::getMemberId).filter(id -> id != null && !id.isBlank()).distinct().toList();
        com.voyra.crm.util.VoucherTrip.check(ids.size(), legs);
        java.time.LocalDate firstFlight = com.voyra.crm.util.VoucherTrip.firstDate(legs);
        document.setTravellers(ids.stream().map(id -> {
            com.voyra.crm.entity.Member m = memberRepository.findByMemberIdAndClientId(id, booking.getClientId())
                    .filter(x -> Boolean.TRUE.equals(x.getIsActive()))
                    .orElseThrow(() -> new IllegalArgumentException("That traveller isn't one of this client's members"));
            return com.voyra.crm.dto.VoucherTraveller.builder().memberId(id).name(m.getName())
                    .paxType(com.voyra.crm.util.PaxTypeCalculator.paxTypeAt(m.getDob(), firstFlight)).build();
        }).toList());
        document.setLegs(legs);
        document.setDetails(com.voyra.crm.util.VoucherTrip.details(legs, document.getDetails()));
    }

    /**
     * A flight's Domestic / International, answered on its voucher. It picks the invoice series and title, so it
     * holds still once the booking is on a draft or issued invoice.
     */
    private void applyTripType(Booking booking, Boolean international) {
        if (international == null || booking.getType() != com.voyra.crm.enums.BookingType.FLIGHT
                || international.equals(Boolean.TRUE.equals(booking.getInternationalTrip()))) {
            return;
        }
        InvoiceLineSource.Invoiced invoiced = bookingService.invoiced(booking.getId());
        if (invoiced.price().signum() > 0 || !invoiced.voucherIds().isEmpty() || invoiced.unnamedAt() != null
                || invoiceLineSource.draftOf(booking.getId()) != null) {
            throw new IllegalStateException("This booking is already on an invoice as "
                    + (Boolean.TRUE.equals(booking.getInternationalTrip()) ? "International" : "Domestic")
                    + " - its trip type can't change");
        }
        booking.setInternationalTrip(international);
    }

    private static java.math.BigDecimal gross(BookingDocument d) {
        return d.getPurchasePrice() != null ? d.getPurchasePrice() : nz(d.getNetCost());
    }

    private static void copyCommission(BookingDocument from, BookingDocument to) {
        to.setCommissionMode(from.getCommissionMode());
        to.setCommissionValue(from.getCommissionValue());
        to.setSupplierTdsRate(from.getSupplierTdsRate());
        to.setCommission(from.getCommission());
        to.setSupplierTds(from.getSupplierTds());
        to.setCommissionInr(from.getCommissionInr());
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v != null ? v : java.math.BigDecimal.ZERO;
    }
}
