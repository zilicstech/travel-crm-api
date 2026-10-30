package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.SupplierInvoiceDraftRequest;
import com.voyra.crm.dto.SupplierInvoiceLineItemRequest;
import com.voyra.crm.dto.SupplierInvoiceListItemResponse;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierInvoiceLineItem;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.SupplierInvoiceKind;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.SupplierLedgerPosting;
import com.voyra.crm.repository.BookingCostComponentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.SupplierInvoiceLineItemRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierInvoiceServiceCoverageTest {

    @Mock private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock private SupplierInvoiceLineItemRepository lineItemRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingCostComponentRepository bookingCostComponentRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private SupplierLedgerService supplierLedgerService;
    @Mock private AuditService auditService;
    @Mock private FileStorageService fileStorageService;
    @Mock private JournalService journalService;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Spy private BaseCurrency baseCurrency = new BaseCurrency(null);

    @InjectMocks private SupplierInvoiceService service;

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 9, 10);

    @BeforeEach
    void owner() {
        as("O1", UserType.AGENCY_OWNER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "u", type, "T1"), null, List.of()));
    }

    private void vendor(Vendor v) {
        when(vendorRepository.findById(v.getId())).thenReturn(Optional.of(v));
    }

    private void agencyInState(String state) {
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(Tenant.builder().id("T1").stateCode(state).build()));
    }

    private static SupplierInvoiceLineItemRequest line(String qty, String price, String discount, String cgst, String sgst, String igst) {
        SupplierInvoiceLineItemRequest l = new SupplierInvoiceLineItemRequest();
        l.setDescription("Line");
        l.setServiceType(ServiceType.FLIGHT);
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPrice(new BigDecimal(price));
        l.setDiscountAmount(discount == null ? null : new BigDecimal(discount));
        l.setCgstAmount(new BigDecimal(cgst));
        l.setSgstAmount(new BigDecimal(sgst));
        l.setIgstAmount(new BigDecimal(igst));
        return l;
    }

    /** A DRAFT bill of 1000 with one matching line, ready to approve. */
    private static SupplierInvoice.SupplierInvoiceBuilder bill() {
        return SupplierInvoice.builder().id("SI1").vendorId("V1").vendorName("Air Co").status(SupplierInvoiceStatus.DRAFT)
                .taxableValue(new BigDecimal("1000")).taxableValueInr(new BigDecimal("1000"))
                .grandTotal(new BigDecimal("1000")).grandTotalInr(new BigDecimal("1000"))
                .balanceDue(new BigDecimal("1000")).balanceDueInr(new BigDecimal("1000"));
    }

    private static SupplierInvoiceLineItem.SupplierInvoiceLineItemBuilder billLine() {
        return SupplierInvoiceLineItem.builder().id("L1").supplierInvoiceId("SI1").taxableValue(new BigDecimal("1000"))
                .lineTotal(new BigDecimal("1000")).cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO);
    }

    private void readyToApprove(SupplierInvoice invoice, SupplierInvoiceLineItem... lines) {
        when(supplierInvoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder(invoice.getId())).thenReturn(List.of(lines));
    }

    private JournalPosting postedJournal() {
        ArgumentCaptor<JournalPosting> posting = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(posting.capture());
        return posting.getValue();
    }

    // ---------------------------------------------------------------- drafts

    @Test
    void aForeignDraftForABookingIsTotalledInItsCurrencyAndInBase() {
        vendor(Vendor.builder().id("V1").name("Air Co").gstNumber("27AAAAA0000A1Z5").stateCode("27").build());
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").leadId("L1").serviceType(ServiceType.FLIGHT).build()));
        SupplierInvoiceDraftRequest r = new SupplierInvoiceDraftRequest();
        r.setVendorId("V1");
        r.setBookingId("B1");
        r.setCurrencyCode("usd");
        r.setFxRateToInr(new BigDecimal("80"));
        r.setTdsRatePercent(new BigDecimal("2"));
        r.setSupplierInvoiceNumber("INV-9");
        r.setLines(List.of(line("2", "100", "10", "0", "0", "18"), line("1", "50", null, "0", "0", "0")));

        SupplierInvoiceResponse response = service.createDraft(r);

        assertThat(response.getKind()).isEqualTo(SupplierInvoiceKind.PURCHASE);
        assertThat(response.getSupplierGstin()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(response.getSupplierStateCode()).isEqualTo("27");
        assertThat(response.getBookingId()).isEqualTo("B1");
        assertThat(response.getLeadId()).isEqualTo("L1");
        assertThat(response.getServiceType()).isEqualTo(ServiceType.FLIGHT);
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getFxRateSource()).isEqualTo("MANUAL");
        assertThat(response.getSubtotal()).isEqualByComparingTo("250");
        assertThat(response.getDiscountTotal()).isEqualByComparingTo("10");
        assertThat(response.getTaxableValue()).isEqualByComparingTo("240");
        assertThat(response.getIgstAmount()).isEqualByComparingTo("18");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("258");
        assertThat(response.getTdsAmount()).isEqualByComparingTo("4.80");
        assertThat(response.getGrandTotalInr()).isEqualByComparingTo("20640");
        assertThat(response.getTaxableValueInr()).isEqualByComparingTo("19200");
        assertThat(response.getBalanceDueInr()).isEqualByComparingTo("20640");
        assertThat(response.getIsReverseCharge()).isFalse();
        assertThat(response.getItcEligibility()).isEqualTo(ItcEligibility.ELIGIBLE);
        assertThat(response.getReceivedOn()).isEqualTo(LocalDate.now());
        assertThat(response.getLines()).extracting(l -> l.getLineTotal().toPlainString()).containsExactly("208.00", "50.00");
        assertThat(response.getLines()).extracting(l -> l.getSortOrder()).containsExactly(0, 1);
        verify(lineItemRepository).saveAll(anyList());
        verify(auditService).recordCreate(AuditEntityType.SUPPLIER_INVOICE, response.getId(), "Air Co / INV-9");
    }

    @Test
    void aStandaloneDraftKeepsWhatWasTypedAndHasNoLinesYet() {
        vendor(Vendor.builder().id("V1").name("Air Co").gstNumber("VENDOR-GST").build());
        SupplierInvoiceDraftRequest r = new SupplierInvoiceDraftRequest();
        r.setVendorId("V1");
        r.setBookingId(" ");
        r.setKind(SupplierInvoiceKind.values()[SupplierInvoiceKind.values().length - 1]);
        r.setSupplierGstin("TYPED-GST");
        r.setCurrencyCode("INR");
        r.setItcEligibility(ItcEligibility.INELIGIBLE);
        r.setIsReverseCharge(true);
        r.setReceivedOn(BILL_DATE);
        r.setDueDate(BILL_DATE);

        SupplierInvoiceResponse response = service.createDraft(r);

        assertThat(response.getKind()).isEqualTo(r.getKind());
        assertThat(response.getSupplierGstin()).isEqualTo("TYPED-GST");
        assertThat(response.getItcEligibility()).isEqualTo(ItcEligibility.INELIGIBLE);
        assertThat(response.getIsReverseCharge()).isTrue();
        assertThat(response.getReceivedOn()).isEqualTo(BILL_DATE);
        assertThat(response.getDueDate()).isEqualTo(BILL_DATE);
        assertThat(response.getFxRateSource()).isEqualTo("INR_IDENTITY");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("0");
        assertThat(response.getLines()).isEmpty();
        assertThat(response.getBookingId()).isNull();
        verifyNoInteractions(bookingRepository);
    }

    @Test
    void aDraftNeedsAKnownVendorAndBooking() {
        SupplierInvoiceDraftRequest r = new SupplierInvoiceDraftRequest();
        r.setVendorId("V9");
        assertThatThrownBy(() -> service.createDraft(r)).isInstanceOf(IllegalArgumentException.class).hasMessage("Vendor not found: V9");

        vendor(Vendor.builder().id("V1").name("Air Co").build());
        r.setVendorId("V1");
        r.setBookingId("B9");
        assertThatThrownBy(() -> service.createDraft(r)).isInstanceOf(IllegalArgumentException.class).hasMessage("Booking not found: B9");
        verify(supplierInvoiceRepository, never()).save(any());
    }

    @Test
    void aForeignDraftNeedsAPositiveRate() {
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        SupplierInvoiceDraftRequest r = new SupplierInvoiceDraftRequest();
        r.setVendorId("V1");
        r.setCurrencyCode("AED");
        assertThatThrownBy(() -> service.createDraft(r)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fxRateToInr is required and must be positive for a bill not in INR");
        r.setFxRateToInr(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.createDraft(r)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fxRateToInr is required and must be positive for a bill not in INR");
    }

    @Test
    void editingADraftWithNoCessOrRoundOffTotalsTheLinesOnly() {
        SupplierInvoice invoice = bill().cessAmount(null).roundOff(null).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        SupplierInvoiceDraftRequest r = new SupplierInvoiceDraftRequest();
        r.setVendorId("V1");
        r.setLines(List.of(line("1", "500", null, "45", "45", "0")));

        SupplierInvoiceResponse response = service.updateDraft("SI1", r);

        assertThat(response.getGrandTotal()).isEqualByComparingTo("590");
        assertThat(response.getCgstAmount()).isEqualByComparingTo("45");
        verify(lineItemRepository).deleteBySupplierInvoiceId("SI1");
    }

    // ---------------------------------------------------------------- auto drafts

    @Test
    void aBookingWithoutComponentsDraftsOneBillAtItsNetCost() {
        vendor(Vendor.builder().id("V1").name("Air Co").stateCode("27").build());
        Booking booking = Booking.builder().id("B1").bookingNumber(42L).type(BookingType.FLIGHT).destination("Goa")
                .netCost(new BigDecimal("4500")).pnr("PNR1").build();

        List<SupplierInvoiceResponse> drafts = service.createAutoDraft(booking, "V1");

        assertThat(drafts).singleElement().satisfies(d -> {
            assertThat(d.getAutoDrafted()).isTrue();
            assertThat(d.getGrandTotal()).isEqualByComparingTo("4500");
            assertThat(d.getReferenceNote()).isEqualTo("PNR1");
            assertThat(d.getCategory()).isEqualTo(BookingType.FLIGHT);
            assertThat(d.getNotes()).isEqualTo("Drafted automatically from booking BK00000042 at the agent's net cost"
                    + " - confirm against the supplier's real invoice before approving.");
            assertThat(d.getLines()).singleElement().satisfies(l -> assertThat(l.getDescription()).isEqualTo("Goa - BK00000042"));
        });
    }

    @Test
    void aBookingWithComponentsDraftsOneBillPerNamedVendor() {
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        vendor(Vendor.builder().id("V2").name("Stay Co").build());
        Booking booking = Booking.builder().id("B1").type(BookingType.PACKAGE).build();
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().vendorId("V1").serviceType(ServiceType.FLIGHT).description("Onward fare").netCost(new BigDecimal("300")).build(),
                BookingCostComponent.builder().vendorId("V2").serviceType(ServiceType.HOTEL).description(null).netCost(new BigDecimal("700")).build(),
                BookingCostComponent.builder().vendorId("V1").serviceType(ServiceType.FLIGHT).description(" ").netCost(new BigDecimal("200")).build(),
                BookingCostComponent.builder().vendorId(null).netCost(new BigDecimal("50")).build(),
                BookingCostComponent.builder().vendorId(" ").netCost(new BigDecimal("60")).build()));

        List<SupplierInvoiceResponse> drafts = service.createAutoDraft(booking, "V1");

        assertThat(drafts).extracting(SupplierInvoiceResponse::getVendorName).containsExactly("Air Co", "Stay Co");
        assertThat(drafts.get(0).getGrandTotal()).isEqualByComparingTo("500");
        assertThat(drafts.get(0).getLines()).extracting(l -> l.getDescription()).containsExactly("Onward fare", "FLIGHT - B1");
        assertThat(drafts.get(1).getLines()).extracting(l -> l.getDescription()).containsExactly("HOTEL - B1");
        assertThat(drafts.get(0).getNotes()).startsWith("Drafted automatically from booking B1's cost components for this vendor");
    }

    @Test
    void aVoucherBillInBaseWithoutAReferenceOrDestinationIsPlain() {
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        Booking booking = Booking.builder().id("B1").type(BookingType.HOTEL).build();

        SupplierInvoiceResponse d = service.draftForVoucher(booking, "V1", " ", "INR", new BigDecimal("900"), BigDecimal.ONE, null, null);

        assertThat(d.getCurrencyCode()).isEqualTo("INR");
        assertThat(d.getFxRateSource()).isEqualTo("INR_IDENTITY");
        assertThat(d.getCommission()).isEqualByComparingTo("0");
        assertThat(d.getSupplierTds()).isEqualByComparingTo("0");
        assertThat(d.getLines()).singleElement().satisfies(l -> assertThat(l.getDescription()).isEqualTo("Booking - B1"));
        verify(supplierInvoiceRepository, never()).findById(any());
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void confirmingAPurchaseStampsTheSuppliersNumberAndBooksIt() {
        SupplierInvoice invoice = bill().build();
        readyToApprove(invoice, billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").paymentTermsDays(15).build());
        agencyInState("27");

        SupplierInvoiceResponse r = service.confirmPurchase("SI1", "  SUP-77 ", BILL_DATE);

        assertThat(r.getSupplierInvoiceNumber()).isEqualTo("SUP-77");
        assertThat(r.getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
        assertThat(r.getInvoiceDate()).isEqualTo(BILL_DATE);
        assertThat(r.getDueDate()).isEqualTo(BILL_DATE.plusDays(15));
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(SupplierLedgerEntryType.BILL_BOOKED);
        assertThat(ledger.getValue().entryDate()).isEqualTo(BILL_DATE);
        assertThat(ledger.getValue().documentNumber()).isEqualTo("SUP-77");
        assertThat(ledger.getValue().narration()).isEqualTo("Supplier bill Air Co / SUP-77 booked");
        assertThat(ledger.getValue().creditAmountInr()).isEqualByComparingTo("1000");
        JournalPosting j = postedJournal();
        assertThat(j.entryDate()).isEqualTo(BILL_DATE);
        assertThat(j.lines()).containsExactly(
                JournalLinePosting.debit(SystemAccount.purchaseCode(com.voyra.crm.enums.InvoiceServiceCategory.MISCELLANEOUS),
                        new BigDecimal("1000"), "Supplier bill Air Co / SUP-77 booked"),
                JournalLinePosting.creditParty(SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", "V1", new BigDecimal("1000.00"),
                        "Supplier bill Air Co / SUP-77 booked"));
    }

    @Test
    void confirmingWithoutADateKeepsTheBillsDateAndBooksItToday() {
        SupplierInvoice invoice = bill().vendorId(null).gstTotalInr(null).build();
        readyToApprove(invoice, billLine().build());
        agencyInState(null);

        SupplierInvoiceResponse r = service.confirmPurchase("SI1", "SUP-1", null);

        assertThat(r.getInvoiceDate()).isNull();
        assertThat(r.getDueDate()).isEqualTo(LocalDate.now());
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryDate()).isEqualTo(LocalDate.now());
        assertThat(postedJournal().entryDate()).isEqualTo(LocalDate.now());
        verifyNoInteractions(vendorRepository);
    }

    @Test
    void aVendorWithoutTermsMakesTheBillDueOnItsDate() {
        SupplierInvoice invoice = bill().invoiceDate(BILL_DATE).build();
        readyToApprove(invoice, billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");

        assertThat(service.approve("SI1", null).getDueDate()).isEqualTo(BILL_DATE);
    }

    @Test
    void aBillWithADueDateKeepsIt() {
        SupplierInvoice invoice = bill().dueDate(BILL_DATE.plusDays(3)).build();
        readyToApprove(invoice, billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").paymentTermsDays(30).build());
        agencyInState("27");

        assertThat(service.approve("SI1", null).getDueDate()).isEqualTo(BILL_DATE.plusDays(3));
    }

    @Test
    void onlyADraftCanBeDeleted() {
        SupplierInvoice invoice = bill().build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));

        service.deleteDraft("SI1");

        verify(lineItemRepository).deleteBySupplierInvoiceId("SI1");
        verify(supplierInvoiceRepository).delete(invoice);
        invoice.setStatus(SupplierInvoiceStatus.APPROVED);
        assertThatThrownBy(() -> service.deleteDraft("SI1")).isInstanceOf(IllegalStateException.class).hasMessage("Only a draft bill can be deleted");
    }

    @Test
    void aBillIsReadWithItsLinesAndFile() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill().fileKey("k").build()));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(billLine().sacCode("998551").build()));

        SupplierInvoiceResponse r = service.get("SI1");

        assertThat(r.getHasFile()).isTrue();
        assertThat(r.getLines()).singleElement().satisfies(l -> assertThat(l.getSacCode()).isEqualTo("998551"));
    }

    @Test
    void anAgentCannotReadPayablesAndAnUnknownBillIsNotFound() {
        assertThatThrownBy(() -> service.get("SI9")).isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");
        as("A1", UserType.AGENT);
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill().build()));
        assertThatThrownBy(() -> service.get("SI1")).isInstanceOf(AccessDeniedException.class).hasMessage("Payables are not accessible to an Agent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBillListNamesBookingsItCanFindAndFlagsOverdueBills() {
        LocalDate past = LocalDate.now().minusDays(5);
        SupplierInvoice overdue = bill().id("S1").bookingId("B1").dueDate(past).build();
        SupplierInvoice missingBooking = bill().id("S2").bookingId("B9").dueDate(past).balanceDue(BigDecimal.ZERO).build();
        SupplierInvoice noBooking = bill().id("S3").dueDate(null).build();
        SupplierInvoice cancelled = bill().id("S4").status(SupplierInvoiceStatus.CANCELLED).dueDate(past).build();
        SupplierInvoice future = bill().id("S5").dueDate(LocalDate.now().plusDays(5)).build();
        when(supplierInvoiceRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(overdue, missingBooking, noBooking, cancelled, future));
        when(bookingRepository.findAllById(List.of("B1", "B9"))).thenReturn(List.of(
                Booking.builder().id("B1").bookingNumber(7L).clientName("Asha").build()));

        List<SupplierInvoiceListItemResponse> rows = service.list("V1", SupplierInvoiceStatus.APPROVED, null);

        assertThat(rows).extracting(SupplierInvoiceListItemResponse::getId).containsExactly("S1", "S2", "S3", "S4", "S5");
        assertThat(rows.get(0).getBookingRef()).isEqualTo("BK00000007");
        assertThat(rows.get(0).getClientName()).isEqualTo("Asha");
        assertThat(rows.get(1).getBookingRef()).isNull();
        assertThat(rows).extracting(SupplierInvoiceListItemResponse::getOverdue).containsExactly(true, false, false, false, false);
    }

    @Test
    void approvingForABookingNeedsTheBill() {
        assertThatThrownBy(() -> service.approveForBooking("SI9", "charge"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");
    }

    @Test
    void aVoucherReferenceIsNotedOnItsBill() {
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        Booking booking = Booking.builder().id("B1").type(BookingType.FLIGHT).build();
        SupplierInvoice stored = SupplierInvoice.builder().id("SI7").notes("Drafted.").build();
        when(supplierInvoiceRepository.findById(any())).thenReturn(Optional.of(stored));

        service.draftForVoucher(booking, "V1", " PNR9 ", null, new BigDecimal("900"), null, null, null);

        assertThat(stored.getNotes()).isEqualTo("Drafted. Voucher PNR9.");
        verify(supplierInvoiceRepository).save(stored);
    }

    @Test
    void aBookingCancellationMayBookItsChargeBill() {
        readyToApprove(bill().build(), billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");
        as("A1", UserType.AGENT);

        assertThat(service.approveForBooking("SI1", "Supplier kept a charge").getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
    }

    @Test
    void aBillWithNoLinesCannotBeApproved() {
        readyToApprove(bill().build());
        assertThatThrownBy(() -> service.approve("SI1", null))
                .isInstanceOf(IllegalStateException.class).hasMessage("At least one line is required to approve a bill");
    }

    // ---------------------------------------------------------------- cost cap

    private void overCap(SupplierInvoice invoice) {
        readyToApprove(invoice, billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");
        when(bookingCostComponentRepository.findByBookingIdAndVendorId("B1", "V1")).thenReturn(List.of(
                BookingCostComponent.builder().netCostInr(new BigDecimal("600")).build(),
                BookingCostComponent.builder().netCostInr(null).netCost(new BigDecimal("300")).build()));
    }

    @Test
    void anAccountantOverTheCapSendsTheBillToTheOwnerWithoutBookingIt() {
        as("AC1", UserType.ACCOUNTANT);
        SupplierInvoice invoice = bill().bookingId("B1").build();
        overCap(invoice);

        SupplierInvoiceResponse r = service.approve("SI1", null);

        assertThat(r.getStatus()).isEqualTo(SupplierInvoiceStatus.PENDING_APPROVAL);
        verify(supplierInvoiceRepository).save(invoice);
        verifyNoInteractions(supplierLedgerService, journalService);
    }

    @Test
    void anAccountantCannotApproveABillWaitingForTheOwner() {
        as("AC1", UserType.ACCOUNTANT);
        overCap(bill().bookingId("B1").status(SupplierInvoiceStatus.PENDING_APPROVAL).build());

        assertThatThrownBy(() -> service.approve("SI1", "please"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("This bill exceeds the booking's quoted cost and needs an Agency Owner's override to approve");
    }

    @Test
    void theOwnerMustGiveAReasonToApproveOverTheCap() {
        overCap(bill().bookingId("B1").build());

        assertThatThrownBy(() -> service.approve("SI1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This bill exceeds the booking's quoted cost cap of 900 by 100 - an override reason is required to approve it anyway");
        verifyNoInteractions(supplierLedgerService);
    }

    @Test
    void theOwnersOverrideIsAudited() {
        overCap(bill().bookingId("B1").build());
        when(bookingRepository.findById("B1")).thenReturn(Optional.empty());

        service.approve("SI1", "Fare rose");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordUpdate(any(), any(), any(), changes.capture());
        assertThat(changes.getValue()).singleElement().satisfies(c -> {
            assertThat(c.getField()).isEqualTo("costCapOverride");
            assertThat(c.getOldValue()).isEqualTo("cap 900");
            assertThat(c.getNewValue()).isEqualTo("Fare rose");
        });
        // the booking could not be read: the purchase is miscellaneous, and the client invoice decides pass-through
        assertThat(postedJournal().lines().get(0).accountCode()).isEqualTo(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code());
    }

    @Test
    void aBillWithinItsCapIsApprovedWithoutAnOverride() {
        SupplierInvoice invoice = bill().bookingId("B1").build();
        readyToApprove(invoice, billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");
        when(bookingCostComponentRepository.findByBookingIdAndVendorId("B1", "V1")).thenReturn(List.of(
                BookingCostComponent.builder().netCostInr(new BigDecimal("1000")).build()));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(Booking.builder().id("B1").type(BookingType.HOTEL).build()));
        // a raised invoice billed as principal keeps the hotel bill a purchase; drafts and unset models are skipped
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(
                        Invoice.builder().id("I0").status(InvoiceLifecycle.DRAFT).billingModel(InvoiceBillingModel.COMMISSION_AGENT).build(),
                        Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED).billingModel(null).build(),
                        Invoice.builder().id("I2").status(InvoiceLifecycle.ISSUED).billingModel(InvoiceBillingModel.PRINCIPAL).build()));

        assertThat(service.approve("SI1", null).getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
        assertThat(postedJournal().lines().get(0).accountCode())
                .isEqualTo(SystemAccount.purchaseCode(com.voyra.crm.enums.InvoiceServiceCategory.HOTEL));
    }

    @Test
    void aBillWithABookingButNoVendorHasNoCap() {
        SupplierInvoice invoice = bill().vendorId(null).bookingId("B1").build();
        readyToApprove(invoice, billLine().build());
        agencyInState("27");
        when(bookingRepository.findById("B1")).thenReturn(Optional.empty());

        assertThat(service.approve("SI1", null).getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
        verifyNoInteractions(bookingCostComponentRepository);
    }

    @Test
    void aZeroBillBooksTheLedgerButNoJournal() {
        SupplierInvoice invoice = bill().taxableValue(BigDecimal.ZERO).taxableValueInr(BigDecimal.ZERO).grandTotal(BigDecimal.ZERO)
                .grandTotalInr(BigDecimal.ZERO).build();
        readyToApprove(invoice, billLine().taxableValue(BigDecimal.ZERO).lineTotal(BigDecimal.ZERO).build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");

        service.approve("SI1", null);

        verify(supplierLedgerService).post(any());
        verifyNoInteractions(journalService);
    }

    // ---------------------------------------------------------------- recorded tax

    @Test
    void theHeaderMustAgreeWithTheLines() {
        readyToApprove(bill().taxableValue(new BigDecimal("999")).build(), billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        assertThatThrownBy(() -> service.approve("SI1", null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Header taxable value does not match the sum of the lines");
    }

    @Test
    void theGrandTotalLessRoundOffMustAgreeWithTheLines() {
        readyToApprove(bill().roundOff(new BigDecimal("0.40")).build(), billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        assertThatThrownBy(() -> service.approve("SI1", null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Header grand total does not match the sum of the lines");
    }

    @Test
    void aBillCannotCarryBothSplits() {
        readyToApprove(bill().grandTotal(new BigDecimal("1100")).build(),
                billLine().lineTotal(new BigDecimal("1050")).cgstAmount(new BigDecimal("50")).build(),
                billLine().id("L2").taxableValue(BigDecimal.ZERO).lineTotal(new BigDecimal("50")).igstAmount(new BigDecimal("50")).build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        assertThatThrownBy(() -> service.approve("SI1", null))
                .isInstanceOf(IllegalStateException.class).hasMessage("A bill cannot carry both CGST/SGST and IGST across its lines");
    }

    @Test
    void cgstFromAnotherStateIsRefused() {
        readyToApprove(bill().supplierStateCode("07").grandTotal(new BigDecimal("1050")).build(),
                billLine().lineTotal(new BigDecimal("1050")).sgstAmount(new BigDecimal("50")).build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");
        assertThatThrownBy(() -> service.approve("SI1", null)).isInstanceOf(IllegalStateException.class)
                .hasMessage("This bill records CGST/SGST but the supplier's state differs from the agency's own - expected IGST");
    }

    @Test
    void igstFromTheSameStateIsRefused() {
        readyToApprove(bill().supplierStateCode("27").grandTotal(new BigDecimal("1180")).build(),
                billLine().lineTotal(new BigDecimal("1180")).igstAmount(new BigDecimal("180")).build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");
        assertThatThrownBy(() -> service.approve("SI1", null)).isInstanceOf(IllegalStateException.class)
                .hasMessage("This bill records IGST but the supplier's state matches the agency's own - expected CGST+SGST");
    }

    @Test
    void theRightSplitForTheStatesIsApproved() {
        SupplierInvoice intra = bill().supplierStateCode("27").grandTotal(new BigDecimal("1100")).gstTotalInr(new BigDecimal("100"))
                .grandTotalInr(new BigDecimal("1100")).build();
        readyToApprove(intra, billLine().lineTotal(new BigDecimal("1100")).sgstAmount(new BigDecimal("50")).cgstAmount(new BigDecimal("50")).build());
        SupplierInvoice inter = bill().id("SI2").supplierStateCode("07").grandTotal(new BigDecimal("1180")).build();
        readyToApprove(inter, billLine().id("L2").lineTotal(new BigDecimal("1180")).igstAmount(new BigDecimal("180")).build());
        SupplierInvoice noState = bill().id("SI3").supplierStateCode(null).grandTotal(new BigDecimal("1180")).build();
        readyToApprove(noState, billLine().id("L3").lineTotal(new BigDecimal("1180")).igstAmount(new BigDecimal("180")).build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        agencyInState("27");

        assertThat(service.approve("SI1", null).getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
        assertThat(service.approve("SI2", null).getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
        assertThat(service.approve("SI3", null).getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
    }

    @Test
    void approvingNeedsTheAgencyOnRecord() {
        readyToApprove(bill().build(), billLine().build());
        vendor(Vendor.builder().id("V1").name("Air Co").build());
        assertThatThrownBy(() -> service.approve("SI1", null)).isInstanceOf(IllegalStateException.class).hasMessage("Agency not found: T1");
    }

    // ---------------------------------------------------------------- commission

    @Test
    void aCommissionChangeNeedsAnOpenBill() {
        assertThatThrownBy(() -> service.setDraftCommission("SI9", BigDecimal.TEN, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill().status(SupplierInvoiceStatus.CANCELLED).build()));
        assertThatThrownBy(() -> service.setDraftCommission("SI1", BigDecimal.TEN, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class).hasMessage("This voucher's supplier bill is already cancelled - its commission can't change");
    }

    @Test
    void aPendingBillsCommissionStillChangesAndMovesItsBalance() {
        SupplierInvoice pending = bill().status(SupplierInvoiceStatus.PENDING_APPROVAL).fxRateToInr(new BigDecimal("2")).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(pending));

        service.setDraftCommission("SI1", new BigDecimal("100"), new BigDecimal("5"));

        assertThat(pending.getBalanceDue()).isEqualByComparingTo("905");
        assertThat(pending.getBalanceDueInr()).isEqualByComparingTo("1810");
        verify(supplierInvoiceRepository).save(pending);
    }

    @Test
    void recallingNeedsTheBillAndSkipsOneWithoutCommission() {
        assertThatThrownBy(() -> service.recallCommission("SI9"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill().commission(null).supplierTds(null).build()));
        service.recallCommission("SI1");
        verify(supplierInvoiceRepository, never()).save(any());
    }

    @Test
    void recallingOnAPendingOrCancelledBillOnlyDropsTheCommission() {
        SupplierInvoice pending = bill().status(SupplierInvoiceStatus.PENDING_APPROVAL).commission(BigDecimal.ZERO).supplierTds(new BigDecimal("4")).build();
        SupplierInvoice cancelled = bill().id("SI2").status(SupplierInvoiceStatus.CANCELLED).commission(new BigDecimal("40")).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(pending));
        when(supplierInvoiceRepository.findById("SI2")).thenReturn(Optional.of(cancelled));

        service.recallCommission("SI1");
        service.recallCommission("SI2");

        assertThat(pending.getSupplierTds()).isEqualByComparingTo("0");
        assertThat(pending.getStatus()).isEqualTo(SupplierInvoiceStatus.PENDING_APPROVAL);
        assertThat(cancelled.getCommission()).isEqualByComparingTo("0");
        assertThat(cancelled.getStatus()).isEqualTo(SupplierInvoiceStatus.CANCELLED);
        verifyNoInteractions(supplierLedgerService, journalService);
    }

    @Test
    void recallingACommissionWithoutTdsOnABookedBillOwesTheWholeCommission() {
        SupplierInvoice paid = bill().status(SupplierInvoiceStatus.APPROVED).commission(new BigDecimal("50")).supplierTds(BigDecimal.ZERO)
                .supplierInvoiceNumber("SUP-1").build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(paid));

        service.recallCommission("SI1");

        String narration = "Commission on supplier bill Air Co / SUP-1 taken back - booking cancelled";
        assertThat(postedJournal().lines()).containsExactly(
                JournalLinePosting.debit(SystemAccount.SUPPLIER_COMMISSION_INCOME.code(), new BigDecimal("50.00"), narration),
                JournalLinePosting.creditParty(SystemAccount.ACCOUNTS_PAYABLE.code(), "VENDOR", "V1", new BigDecimal("50.00"), narration));
        assertThat(paid.getBalanceDue()).isEqualByComparingTo("1000");
    }

    @Test
    void recallingOnlyTheTdsOnABookedBillReversesTheClaimAndOwesNothingMore() {
        SupplierInvoice booked = bill().status(SupplierInvoiceStatus.PARTIALLY_PAID).commission(BigDecimal.ZERO)
                .supplierTds(new BigDecimal("8")).amountPaid(new BigDecimal("200")).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(booked));

        service.recallCommission("SI1");

        assertThat(postedJournal().lines()).extracting(JournalLinePosting::accountCode)
                .containsExactly(SystemAccount.SUPPLIER_COMMISSION_INCOME.code(), SystemAccount.TDS_RECEIVABLE.code());
        assertThat(booked.getStatus()).isEqualTo(SupplierInvoiceStatus.PARTIALLY_PAID);
    }

    // ---------------------------------------------------------------- cancel and files

    @Test
    void cancellingABookedBillReversesItsLedgerAndJournals() {
        SupplierInvoice invoice = bill().status(SupplierInvoiceStatus.APPROVED).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_BILL_BOOKED))
                .thenReturn(Optional.of(JournalEntry.builder().id("J1").build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.SUPPLIER_INVOICE, "SI1", JournalPurpose.SUPPLIER_COMMISSION_RECALLED))
                .thenReturn(Optional.of(JournalEntry.builder().id("J2").build()));

        SupplierInvoiceResponse r = service.cancel("SI1", "Duplicate");

        assertThat(r.getStatus()).isEqualTo(SupplierInvoiceStatus.CANCELLED);
        assertThat(r.getCancelReason()).isEqualTo("Duplicate");
        assertThat(r.getCancelledBy()).isEqualTo("O1");
        assertThat(r.getBalanceDue()).isEqualByComparingTo("0");
        ArgumentCaptor<SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(SupplierLedgerPosting.class);
        verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(SupplierLedgerEntryType.REVERSAL);
        assertThat(ledger.getValue().debitAmountInr()).isEqualByComparingTo("1000");
        verify(journalService).reverse("J1", "Supplier bill cancelled: Duplicate");
        verify(journalService).reverse("J2", "Supplier bill cancelled: Duplicate");
    }

    @Test
    void cancellingABookedBillWithoutJournalsOnlyReversesTheLedger() {
        SupplierInvoice invoice = bill().status(SupplierInvoiceStatus.APPROVED).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));

        service.cancelForBooking("SI1", "Booking cancelled");

        verify(supplierLedgerService).post(any());
        verifyNoInteractions(journalService);
    }

    @Test
    void aPaidBillOrAnUnknownOneCannotBeCancelled() {
        assertThatThrownBy(() -> service.cancelForBooking("SI9", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Supplier bill not found: SI9");
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill().status(SupplierInvoiceStatus.APPROVED)
                .amountPaid(new BigDecimal("1")).build()));
        assertThatThrownBy(() -> service.cancel("SI1", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessage("This bill has payments recorded against it - reverse them first");
    }

    @Test
    void aFileIsStoredUnderTheAgencyAndReadBack() {
        SupplierInvoice invoice = bill().build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        MockMultipartFile file = new MockMultipartFile("file", "bill.pdf", "application/pdf", new byte[]{1, 2});
        when(fileStorageService.store("T1", "supplier-invoices", "SI1", file)).thenReturn("T1/supplier-invoices/SI1/bill.pdf");
        ByteArrayResource resource = new ByteArrayResource(new byte[]{1, 2});
        when(fileStorageService.retrieve("T1/supplier-invoices/SI1/bill.pdf")).thenReturn(resource);

        assertThat(service.attachFile("SI1", file).getHasFile()).isTrue();
        SupplierInvoiceService.SupplierInvoiceFileContent content = service.downloadFile("SI1");

        assertThat(content.resource()).isSameAs(resource);
        assertThat(content.filename()).isEqualTo("bill.pdf");
        assertThat(content.contentType()).isEqualTo("application/pdf");
        verify(supplierInvoiceRepository).save(invoice);
    }

    @Test
    void aBillWithoutAFileHasNothingToDownload() {
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill().build()));
        assertThatThrownBy(() -> service.downloadFile("SI1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No file attached to this bill");
        verifyNoInteractions(fileStorageService);
    }
}
