package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceListItemResponse;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.dto.InvoiceTaxRequest;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.VoucherLeg;
import com.voyra.crm.dto.VoucherTraveller;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingCostComponent;
import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.InvoiceTax;
import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.JournalLine;
import com.voyra.crm.entity.TaxRateConfig;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.BookingDocumentType;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.InvoiceBillingModel;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.SystemAccount;
import com.voyra.crm.enums.TaxKind;
import com.voyra.crm.enums.TaxLineMode;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingCostComponentRepository;
import com.voyra.crm.repository.BookingDocumentRepository;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.InvoiceTaxRepository;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.JournalLineRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.TaxRateConfigRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.util.FinancialYear;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch coverage for InvoiceDocumentService, with its line source mocked so each path is set up directly. */
@ExtendWith(MockitoExtension.class)
class InvoiceDocumentServiceCoverageTest {

    @Mock InvoiceRepository invoiceRepository;
    @Mock InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock InvoiceTaxRepository invoiceTaxRepository;
    @Mock PaymentReceiptRepository paymentReceiptRepository;
    @Mock BookingRepository bookingRepository;
    @Mock BookingPassengerRepository bookingPassengerRepository;
    @Mock BookingSectorRepository bookingSectorRepository;
    @Mock BookingCostComponentRepository bookingCostComponentRepository;
    @Mock JournalService journalService;
    @Mock JournalEntryRepository journalEntryRepository;
    @Mock JournalLineRepository journalLineRepository;
    @Mock ClientService clientService;
    @Mock TenantRepository tenantRepository;
    @Mock TaxRateConfigRepository taxRateConfigRepository;
    @Mock TaxEngine taxEngine;
    @Mock DocumentNumberService documentNumberService;
    @Mock AuditService auditService;
    @Mock CustomerLedgerService customerLedgerService;
    @Mock BookingAccountingSync bookingAccountingSync;
    @Mock PaymentReceiptService paymentReceiptService;
    @Mock FileStorageService fileStorageService;
    @Spy BaseCurrency baseCurrency = new BaseCurrency(null);
    @Mock InvoiceLineSource invoiceLineSource;
    @Mock BookingDocumentRepository bookingDocumentRepository;
    @Mock AgentRepository agentRepository;
    @InjectMocks InvoiceDocumentService service;

    private static final LocalDate TODAY = FinancialYear.today();

    @BeforeEach
    void signInAsAccountant() {
        signInAs("AC1", UserType.ACCOUNTANT);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "user", type, "T1"), null, List.of()));
    }

    private Tenant agency() {
        return Tenant.builder().id("T1").agencyName("Global Explorer").gstNumber("27AAAAA0000A1Z5")
                .stateCode("27").address("Mumbai").build();
    }

    private Booking booking(String id, long number) {
        return Booking.builder().id(id).bookingNumber(number).clientId("K1").clientName("Asha").agentId("A1").agentName("Liam")
                .vendorId("V1").supplier("Agoda").type(BookingType.HOTEL).destination("Goa").leadId("L-" + id)
                .bookingStatus(BookingStatus.CONFIRMED).build();
    }

    private static InvoiceLineItemRequest lineReq(String bookingId, String voucherId, String description, String price) {
        InvoiceLineItemRequest l = new InvoiceLineItemRequest();
        l.setBookingId(bookingId);
        l.setBookingDocumentId(voucherId);
        l.setDescription(description);
        l.setQuantity(BigDecimal.ONE);
        l.setUnitPrice(new BigDecimal(price));
        return l;
    }

    private static InvoiceLineItem line(String id, String bookingId, String voucherId, String subtotal) {
        BigDecimal v = new BigDecimal(subtotal);
        return InvoiceLineItem.builder().id(id).invoiceId("I1").bookingId(bookingId).bookingDocumentId(voucherId)
                .description("line " + id).quantity(BigDecimal.ONE).unitPrice(v).lineSubtotal(v).taxableValue(v).lineTotal(v).build();
    }

    private void stubDraftable(Booking b) {
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(b.getId(), InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.DRAFT))
                .thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(Client.builder().id("K1").name("Asha").stateCode("27").build());
    }

    private Invoice savedInvoice() {
        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(saved.capture());
        return saved.getValue();
    }

    // ------------------------------------------------------------------ createDrafts

    @Test
    void createDraftsNeedsAtLeastOneRealBookingId() {
        assertThatThrownBy(() -> service.createDrafts(null)).hasMessage("Pick at least one booking");
        assertThatThrownBy(() -> service.createDrafts(Arrays.asList(null, " "))).hasMessage("Pick at least one booking");
        when(bookingRepository.findByIdForUpdate("B9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createDrafts(List.of("B9"))).hasMessage("Booking not found: B9");
    }

    @Test
    void createDraftsRefusesUnconfirmedAndCancelledBookings() {
        Booking pending = booking("B1", 1);
        pending.setBookingStatus(BookingStatus.PENDING);
        Booking cancelled = booking("B2", 2);
        cancelled.setBookingStatus(BookingStatus.CANCELLED);
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(pending));
        when(bookingRepository.findByIdForUpdate("B2")).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.createDrafts(List.of("B1")))
                .hasMessage("BK00000001 isn't confirmed yet - add its priced voucher first");
        assertThatThrownBy(() -> service.createDrafts(List.of("B2")))
                .hasMessage("BK00000002 is cancelled - it can't be invoiced");
    }

    @Test
    void combiningBookingsNeedsASupplierOnEach() {
        Booking b1 = booking("B1", 1);
        Booking noVendor = booking("B2", 2);
        noVendor.setVendorId(null);
        Booking blankVendor = booking("B3", 3);
        blankVendor.setVendorId(" ");
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(b1));
        when(bookingRepository.findByIdForUpdate("B2")).thenReturn(Optional.of(noVendor));
        when(bookingRepository.findByIdForUpdate("B3")).thenReturn(Optional.of(blankVendor));
        when(invoiceLineSource.draftOf("B1")).thenReturn(null);
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(List.of(lineReq("B1", "D1", "x", "100")), BigDecimal.TEN));

        assertThatThrownBy(() -> service.createDrafts(List.of("B1", "B2")))
                .hasMessage("BK00000002 has no supplier - set it on the booking before combining it");
        assertThatThrownBy(() -> service.createDrafts(List.of("B1", "B3")))
                .hasMessage("BK00000003 has no supplier - set it on the booking before combining it");
    }

    @Test
    void combiningBookingsNeedsTheSameClientSupplierAgentAndType() {
        Booking b1 = booking("B1", 1);
        Booking otherClient = booking("B2", 2);
        otherClient.setClientId("K2");
        Booking otherVendor = booking("B3", 3);
        otherVendor.setVendorId("V2");
        Booking otherAgent = booking("B4", 4);
        otherAgent.setAgentId("A2");
        Booking otherType = booking("B5", 5);
        otherType.setType(BookingType.FLIGHT);
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(b1));
        for (Booking b : List.of(otherClient, otherVendor, otherAgent, otherType)) {
            when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        }
        when(invoiceLineSource.draftOf("B1")).thenReturn(null);
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(List.of(lineReq("B1", "D1", "x", "100")), BigDecimal.TEN));

        for (String other : List.of("B2", "B3", "B4", "B5")) {
            assertThatThrownBy(() -> service.createDrafts(List.of("B1", other)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageEndingWith("differs from BK00000001");
        }
    }

    @Test
    void aFullyInvoicedBookingIsNotDraftedAgain() {
        Booking b1 = booking("B1", 1);
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(b1));
        when(invoiceLineSource.draftOf("B1")).thenReturn(null);
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL)).thenReturn(new InvoiceLineSource.Lines(List.of(), BigDecimal.ZERO));

        assertThatThrownBy(() -> service.createDrafts(List.of("B1"))).hasMessage("BK00000001 is fully invoiced");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void aCombinedDraftOutsideAnInrBaseCarriesNoDefaultGst() {
        doReturn("AED").when(baseCurrency).code();
        Booking numbered = booking("B1", 7);
        numbered.setBookingStatus(BookingStatus.COMPLETED);
        Booking unnumbered = booking("B2", 0);
        unnumbered.setBookingNumber(null);
        when(bookingRepository.findByIdForUpdate("B1")).thenReturn(Optional.of(numbered));
        when(bookingRepository.findByIdForUpdate("B2")).thenReturn(Optional.of(unnumbered));
        when(invoiceLineSource.linesFor(numbered, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B1", "D1", "Stay", "1000.00"))), new BigDecimal("800.00")));
        when(invoiceLineSource.linesFor(unnumbered, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B2", "D2", "Stay 2", "500.00"))), new BigDecimal("450.00")));
        when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(eq(TaxKind.GST), any()))
                .thenReturn(Optional.of(TaxRateConfig.builder().id("G18").label("GST 18%").ratePercent(new BigDecimal("18")).build()));
        stubDraftable(numbered);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));

        InvoiceResponse res = service.createDrafts(List.of("B2", "B1"));

        Invoice inv = savedInvoice();
        assertThat(inv.getBookingId()).isEqualTo("B1");
        assertThat(inv.getCurrencyCode()).isEqualTo("AED");
        assertThat(inv.getBillingModel()).isEqualTo(InvoiceBillingModel.COMMISSION_AGENT);
        assertThat(inv.getPassThroughAmount()).isEqualByComparingTo("1250.00");
        assertThat(inv.getTaxableValue()).isEqualByComparingTo("1500.00");
        assertThat(inv.getGrandTotal()).isEqualByComparingTo("1500.00");
        assertThat(res.getTaxes()).isEmpty();
        assertThat(inv.getAgencyLegalName()).isEqualTo("Global Explorer");
    }

    // ------------------------------------------------------------------ removeBookingFromDraft

    @Test
    void aDraftBillingOnlyThatBookingIsDeleted() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(draft));
        when(invoiceLineSource.bookingIdsOf(draft)).thenReturn(List.of("B1"));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        service.removeBookingFromDraft("B1");

        verify(invoiceLineItemRepository).deleteByInvoiceId("I1");
        verify(invoiceTaxRepository).deleteByInvoiceId("I1");
        verify(invoiceRepository).delete(draft);
    }

    @Test
    void aCombinedDraftIsRebuiltForTheOtherBookingsKeepingWordingAndTaxes() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").leadId("L-B1").status(InvoiceLifecycle.DRAFT)
                .serviceCategory(InvoiceServiceCategory.HOTEL).billingModel(InvoiceBillingModel.PRINCIPAL).build();
        Booking b2 = booking("B2", 2);
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(draft));
        when(invoiceLineSource.bookingIdsOf(draft)).thenReturn(List.of("B1", "B2"));
        when(bookingRepository.findById("B2")).thenReturn(Optional.of(b2));
        InvoiceLineItem kept = line("LI2", "B2", "D2", "500.00");
        kept.setDescription("  Sea view stay  ");
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("LI1", "B1", "D1", "100.00"), kept));
        when(invoiceLineSource.linesFor(b2, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B2", "D2", "Stay", "600.00"))), new BigDecimal("550.00")));
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(InvoiceTax.builder()
                .label("Service charge").mode(TaxLineMode.PERCENT).ratePercent(new BigDecimal("5")).visibleToCustomer(false).build()));

        service.removeBookingFromDraft("B1");

        assertThat(draft.getBookingId()).isEqualTo("B2");
        assertThat(draft.getLeadId()).isEqualTo("L-B2");
        assertThat(draft.getPassThroughAmount()).isEqualByComparingTo("550.00");
        assertThat(draft.getTaxableValue()).isEqualByComparingTo("600.00");
        assertThat(draft.getOtherTaxTotal()).isEqualByComparingTo("30.00");
        assertThat(draft.getGrandTotal()).isEqualByComparingTo("630.00");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceLineItem>> lines = ArgumentCaptor.forClass(List.class);
        verify(invoiceLineItemRepository).saveAll(lines.capture());
        assertThat(lines.getValue()).singleElement().satisfies(l -> assertThat(l.getDescription()).isEqualTo("Sea view stay"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceTax>> taxes = ArgumentCaptor.forClass(List.class);
        verify(invoiceTaxRepository).saveAll(taxes.capture());
        assertThat(taxes.getValue()).singleElement().satisfies(t -> {
            assertThat(t.getAmount()).isEqualByComparingTo("30.00");
            assertThat(t.getVisibleToCustomer()).isFalse();
            assertThat(t.getTaxKind()).isNull();
        });
        verify(invoiceRepository).save(draft);
        verify(invoiceLineItemRepository).deleteByInvoiceId("I1");
    }

    @Test
    void aCombinedDraftWithNoCategoryUsesEachBookingsOwn() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT)
                .billingModel(InvoiceBillingModel.PRINCIPAL).build();
        Booking b2 = booking("B2", 2);
        when(invoiceRepository.findDraftsForBooking("B1")).thenReturn(List.of(draft));
        when(invoiceLineSource.bookingIdsOf(draft)).thenReturn(List.of("B1", "B2"));
        when(bookingRepository.findById("B2")).thenReturn(Optional.of(b2));
        when(invoiceLineSource.linesFor(b2, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B2", "D2", "Stay", "600.00"))), BigDecimal.ZERO));

        service.removeBookingFromDraft("B1");

        assertThat(draft.getBookingId()).isEqualTo("B2");
        assertThat(draft.getTaxableValue()).isEqualByComparingTo("600.00");
        assertThat(draft.getPassThroughAmount()).isNull();
    }

    // ------------------------------------------------------------------ weightOf

    @Test
    void aBookingsWeightFollowsItsShareOfTheLines() {
        Invoice combined = Invoice.builder().id("I1").bookingId("B1").build();
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(line("L1", "B1", null, "100.00"), line("L2", "B2", null, "300.00")));
        Invoice empty = Invoice.builder().id("I2").build();
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I2")).thenReturn(List.of());

        assertThat(service.weightOf(combined, "B2")).isEqualByComparingTo("0.75");
        assertThat(service.weightOf(combined, "B9")).isEqualByComparingTo("0");
        assertThat(service.weightOf(empty, "B1")).isEqualByComparingTo("1");
    }

    // ------------------------------------------------------------------ createDraft / draftFrom

    @Test
    void createDraftNeedsAnExistingBooking() {
        InvoiceDraftRequest none = new InvoiceDraftRequest();
        InvoiceDraftRequest blank = new InvoiceDraftRequest();
        blank.setBookingId(" ");
        InvoiceDraftRequest unknown = new InvoiceDraftRequest();
        unknown.setBookingId("B9");
        when(bookingRepository.findById("B9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createDraft(none)).hasMessage("bookingId is required");
        assertThatThrownBy(() -> service.createDraft(blank)).hasMessage("bookingId is required");
        assertThatThrownBy(() -> service.createDraft(unknown)).hasMessage("Booking not found: B9");
    }

    @Test
    void aDraftWithoutTermsTakesTheAgencysAndTheLegalName() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        Tenant agency = agency();
        agency.setLegalName("Global Explorer Pvt Ltd");
        agency.setInvoiceTerms("Payable in 7 days");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setTerms(" ");
        InvoiceLineItemRequest withCharges = lineReq("B2", null, "Stay", "1000.00");
        withCharges.setCharges(Map.of("base", BigDecimal.ONE));
        withCharges.setDiscountAmount(new BigDecimal("100.00"));
        req.setLines(List.of(withCharges));

        service.createDraft(req);

        Invoice inv = savedInvoice();
        assertThat(inv.getTerms()).isEqualTo("Payable in 7 days");
        assertThat(inv.getAgencyLegalName()).isEqualTo("Global Explorer Pvt Ltd");
        assertThat(inv.getDiscountTotal()).isEqualByComparingTo("100.00");
        assertThat(inv.getTaxableValue()).isEqualByComparingTo("900.00");
        assertThat(withCharges.getCharges()).isNull();
        assertThat(withCharges.getBookingId()).isEqualTo("B2");
    }

    @Test
    void aDraftKeepsItsOwnTermsAndIgnoresBlankAgencyTerms() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        Tenant agency = agency();
        agency.setInvoiceTerms(" ");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));
        InvoiceDraftRequest own = new InvoiceDraftRequest();
        own.setBookingId("B1");
        own.setTerms("Own terms");
        own.setLines(List.of(lineReq(null, null, "x", "10.00")));

        service.createDraft(own);

        assertThat(savedInvoice().getTerms()).isEqualTo("Own terms");
    }

    @Test
    void aDraftWithNoTermsAnywhereHasNone() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        Tenant agency = agency();
        agency.setInvoiceTerms(" ");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setLines(List.of(lineReq(null, null, "x", "10.00")));
        req.setCurrencyCode(" ");
        req.setInvoiceDate(TODAY.minusDays(1));

        service.createDraft(req);

        Invoice inv = savedInvoice();
        assertThat(inv.getTerms()).isNull();
        assertThat(inv.getCurrencyCode()).isEqualTo("INR");
        assertThat(inv.getInvoiceDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void aDraftCannotBeDatedAhead() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setInvoiceDate(TODAY.plusDays(1));

        assertThatThrownBy(() -> service.createDraft(req)).hasMessage("The invoice date can't be in the future");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void anUnknownAgencyIsAnError() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.empty());
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");

        assertThatThrownBy(() -> service.createDraft(req)).hasMessage("Agency not found: T1");
    }

    @Test
    void aForeignCurrencyDraftConvertsTheVoucherPricesAndPassThroughFromBase() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        InvoiceLineItemRequest voucher = lineReq("B1", "D1", "Stay", "9050.00");
        Map<String, BigDecimal> charges = new LinkedHashMap<>();
        charges.put("base", new BigDecimal("9050.00"));
        charges.put("yq", null);
        voucher.setCharges(charges);
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(voucher)), new BigDecimal("8145.00")));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setCurrencyCode("usd");
        req.setFxRateToInr(new BigDecimal("90.50"));
        req.setLines(List.of());

        InvoiceResponse res = service.createDraft(req);

        Invoice inv = savedInvoice();
        assertThat(inv.getCurrencyCode()).isEqualTo("USD");
        assertThat(inv.getPassThroughAmount()).isEqualByComparingTo("90.00");
        assertThat(voucher.getUnitPrice()).isEqualByComparingTo("100.00");
        assertThat(voucher.getCharges()).containsEntry("base", new BigDecimal("100.00")).containsEntry("yq", null);
        assertThat(inv.getTaxableValue()).isEqualByComparingTo("100.00");
        assertThat(inv.getGrandTotalInr()).isEqualByComparingTo("9050.00");
        assertThat(res.getPassThroughAmount()).isEqualByComparingTo("90.00");
    }

    @Test
    void aForeignDraftNeedsAPositiveRate() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        InvoiceDraftRequest noRate = new InvoiceDraftRequest();
        noRate.setBookingId("B1");
        noRate.setCurrencyCode("USD");
        InvoiceDraftRequest zero = new InvoiceDraftRequest();
        zero.setBookingId("B1");
        zero.setCurrencyCode("USD");
        zero.setFxRateToInr(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createDraft(noRate)).hasMessage("Enter the rate: what 1 USD is in INR");
        assertThatThrownBy(() -> service.createDraft(zero)).hasMessage("Enter the rate: what 1 USD is in INR");
    }

    @Test
    void aForeignDraftAtParLeavesTheVoucherPricesAsTheyAre() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B1", "D1", "Stay", "1000.00"))), null));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setCurrencyCode("USD");
        req.setFxRateToInr(BigDecimal.ONE);

        service.createDraft(req);

        Invoice inv = savedInvoice();
        assertThat(inv.getTaxableValue()).isEqualByComparingTo("1000.00");
        assertThat(inv.getPassThroughAmount()).isNull();
    }

    @Test
    void aForeignDraftWithNoPassThroughKeepsNone() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B1", "D1", "Stay", "1000.00"))), null));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setCurrencyCode("USD");
        req.setFxRateToInr(new BigDecimal("50"));

        service.createDraft(req);

        Invoice inv = savedInvoice();
        assertThat(inv.getTaxableValue()).isEqualByComparingTo("20.00");
        assertThat(inv.getPassThroughAmount()).isNull();
    }

    @Test
    void aGivenPassThroughIsKeptWhenTheVouchersAreUsed() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(lineReq("B1", "D1", "Stay", "1000.00"))), new BigDecimal("900.00")));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setPassThroughAmount(new BigDecimal("700.00"));

        service.createDraft(req);

        assertThat(savedInvoice().getPassThroughAmount()).isEqualByComparingTo("700.00");
    }

    @Test
    void aBookingWithNothingLeftAndNoTravellersFallsBackToItsOwnLine() {
        Booking b1 = booking("B1", 1);
        b1.setSellingPrice(new BigDecimal("1200.00"));
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL)).thenReturn(new InvoiceLineSource.Lines(List.of(), BigDecimal.ZERO));
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of());
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");

        service.createDraft(req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InvoiceLineItem>> lines = ArgumentCaptor.forClass(List.class);
        verify(invoiceLineItemRepository).saveAll(lines.capture());
        assertThat(lines.getValue()).allSatisfy(l -> assertThat(l.getBookingId()).isEqualTo("B1"));
        verify(bookingSectorRepository, never()).findByBookingPassengerIdInOrderBySortOrderAsc(anyList());
    }

    // ------------------------------------------------------------------ taxes

    private InvoiceTaxRequest taxReq(String configId, TaxLineMode mode, String rate, String flat) {
        InvoiceTaxRequest t = new InvoiceTaxRequest();
        t.setLabel("tax " + configId);
        t.setTaxRateConfigId(configId);
        t.setMode(mode);
        t.setRatePercent(rate == null ? null : new BigDecimal(rate));
        t.setFlatAmount(flat == null ? null : new BigDecimal(flat));
        return t;
    }

    @Test
    void taxRowsSplitGstByTreatmentAndAddTcsAndCustomTaxes() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(taxRateConfigRepository.findById("GST")).thenReturn(Optional.of(TaxRateConfig.builder().id("GST").taxKind(TaxKind.GST).build()));
        when(taxRateConfigRepository.findById("TCS")).thenReturn(Optional.of(TaxRateConfig.builder().id("TCS").taxKind(TaxKind.TCS).tcsSection("206C(1G)").build()));
        when(taxEngine.resolveTreatment(eq("K1"), any(), eq(false), eq("INR"))).thenReturn(TaxTreatment.INTER_STATE);
        when(taxEngine.resolvePlaceOfSupplyCode(eq("K1"), any())).thenReturn("29");
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setBillingModel(InvoiceBillingModel.PRINCIPAL);
        req.setLines(List.of(lineReq(null, null, "Package", "10000.00")));
        req.setTaxes(List.of(
                taxReq("GST", TaxLineMode.PERCENT, "5", null),
                taxReq("TCS", TaxLineMode.PERCENT, "5", null),
                taxReq("TCS", TaxLineMode.PERCENT, null, null),
                taxReq(" ", TaxLineMode.FLAT, null, "250.00"),
                taxReq(null, TaxLineMode.FLAT, null, null)));

        InvoiceResponse res = service.createDraft(req);

        Invoice inv = savedInvoice();
        assertThat(inv.getTaxTreatment()).isEqualTo(TaxTreatment.INTER_STATE);
        assertThat(inv.getPlaceOfSupplyCode()).isEqualTo("29");
        assertThat(inv.getIgstAmount()).isEqualByComparingTo("500.00");
        assertThat(inv.getCgstAmount()).isEqualByComparingTo("0");
        assertThat(inv.getTcsAmount()).isEqualByComparingTo("500.00");
        assertThat(inv.getTcsRatePercent()).isEqualByComparingTo("5");
        assertThat(inv.getTcsSection()).isEqualTo("206C(1G)");
        assertThat(inv.getTcsBaseAmount()).isEqualByComparingTo("10000.00");
        assertThat(inv.getOtherTaxTotal()).isEqualByComparingTo("250.00");
        assertThat(inv.getGrandTotal()).isEqualByComparingTo("11250.00");
        assertThat(inv.getBalanceDue()).isEqualByComparingTo("11250.00");
        assertThat(res.getTaxes()).hasSize(5);
        assertThat(res.getTaxes().get(0).getTaxRateConfigId()).isEqualTo("GST");
        assertThat(res.getTaxes().get(3).getTaxRateConfigId()).isNull();
        assertThat(res.getTaxes()).allSatisfy(t -> assertThat(t.getVisibleToCustomer()).isTrue());
    }

    @Test
    void anUnknownTaxConfigIsRefused() {
        Booking b1 = booking("B1", 1);
        stubDraftable(b1);
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(taxRateConfigRepository.findById("NOPE")).thenReturn(Optional.empty());
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBookingId("B1");
        req.setLines(List.of(lineReq(null, null, "Package", "100.00")));
        req.setTaxes(List.of(taxReq("NOPE", TaxLineMode.PERCENT, "5", null)));

        assertThatThrownBy(() -> service.createDraft(req)).hasMessage("Tax rate config not found: NOPE");
    }

    // ------------------------------------------------------------------ updateDraft

    @Test
    void onlyADraftCanBeUpdated() {
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED).build()));

        assertThatThrownBy(() -> service.updateDraft("I1", new InvoiceDraftRequest())).isInstanceOf(IllegalStateException.class);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void updatingADraftWhoseBookingIsGoneIsAnError() {
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).build()));
        when(bookingRepository.findById("B1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDraft("I1", new InvoiceDraftRequest())).hasMessage("Booking not found: B1");
    }

    @Test
    void updatingADraftRepricesFromTheVouchersAndKeepsTheAccountantsWording() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).build();
        Booking b1 = booking("B1", 1);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(invoiceLineSource.bookingIdsOf(draft)).thenReturn(List.of("B1"));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of());
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL)).thenReturn(new InvoiceLineSource.Lines(new ArrayList<>(List.of(
                lineReq("B1", "D1", "Stay", "800.00"), lineReq("B1", null, "Extra", "200.00"), lineReq("B1", null, "Tax", "0.00"))),
                new BigDecimal("700.00")));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setBillingModel(InvoiceBillingModel.PRINCIPAL);
        req.setNotes("note");
        req.setLines(List.of(lineReq("B1", "D1", " Deluxe room ", "1"), lineReq("B1", "D9", " ", "1"),
                lineReq("B1", "D8", null, "1"), lineReq("B1", null, "Late checkout", "1")));

        InvoiceResponse res = service.updateDraft("I1", req);

        assertThat(draft.getServiceCategory()).isEqualTo(InvoiceServiceCategory.HOTEL);
        assertThat(draft.getBillingModel()).isEqualTo(InvoiceBillingModel.PRINCIPAL);
        assertThat(draft.getUpdatedBy()).isEqualTo("AC1");
        assertThat(draft.getPassThroughAmount()).isEqualByComparingTo("700.00");
        assertThat(draft.getTaxableValue()).isEqualByComparingTo("1000.00");
        assertThat(draft.getNotes()).isEqualTo("note");
        assertThat(res.getLines()).extracting(l -> l.getDescription()).containsExactly("Deluxe room", "Late checkout", "Tax");
        verify(invoiceLineItemRepository).deleteByInvoiceId("I1");
        verify(invoiceTaxRepository).deleteByInvoiceId("I1");
    }

    @Test
    void updatingADraftKeepsItsBillingModelOrDefaultsIt() {
        Invoice keeps = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT)
                .serviceCategory(InvoiceServiceCategory.HOTEL).billingModel(InvoiceBillingModel.PRINCIPAL).passThroughAmount(new BigDecimal("5")).build();
        Invoice defaults = Invoice.builder().id("I2").bookingId("B1").status(InvoiceLifecycle.DRAFT)
                .serviceCategory(InvoiceServiceCategory.HOTEL).build();
        Booking b1 = booking("B1", 1);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(keeps));
        when(invoiceRepository.findById("I2")).thenReturn(Optional.of(defaults));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(invoiceLineSource.bookingIdsOf(any())).thenReturn(List.of("B1"));
        // Nothing left to bill: the draft keeps its lines, those with no booking belonging to its first.
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL)).thenReturn(new InvoiceLineSource.Lines(List.of(), BigDecimal.ZERO));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", null, null, "300.00"), line("L2", "B2", null, "50.00")));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I2")).thenReturn(List.of());

        service.updateDraft("I1", new InvoiceDraftRequest());
        service.updateDraft("I2", new InvoiceDraftRequest());

        assertThat(keeps.getBillingModel()).isEqualTo(InvoiceBillingModel.PRINCIPAL);
        assertThat(keeps.getTaxableValue()).isEqualByComparingTo("300.00");
        assertThat(keeps.getPassThroughAmount()).isEqualByComparingTo("5");
        assertThat(defaults.getBillingModel()).isEqualTo(InvoiceServiceCategory.HOTEL.defaultBillingModel());
    }

    @Test
    void rederivingAForeignDraftConvertsFromBase() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT)
                .serviceCategory(InvoiceServiceCategory.HOTEL).build();
        Booking b1 = booking("B1", 1);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(invoiceLineSource.bookingIdsOf(draft)).thenReturn(List.of("B1"));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of());
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL)).thenReturn(new InvoiceLineSource.Lines(
                new ArrayList<>(List.of(lineReq("B1", "D1", "Stay", "1000.00"))), new BigDecimal("500.00")));
        InvoiceDraftRequest req = new InvoiceDraftRequest();
        req.setCurrencyCode("EUR");
        req.setFxRateToInr(new BigDecimal("100"));

        service.updateDraft("I1", req);

        assertThat(draft.getTaxableValue()).isEqualByComparingTo("10.00");
        assertThat(draft.getPassThroughAmount()).isEqualByComparingTo("5.00");
        assertThat(draft.getFxRateSource()).isEqualTo(com.voyra.crm.enums.FxRateSource.MANUAL);
    }

    @Test
    void aRederivedBookingThatIsGoneIsAnError() {
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking("B1", 1)));
        when(bookingRepository.findById("B2")).thenReturn(Optional.empty());
        when(invoiceLineSource.bookingIdsOf(draft)).thenReturn(List.of("B2"));

        assertThatThrownBy(() -> service.updateDraft("I1", new InvoiceDraftRequest())).hasMessage("Booking not found: B2");
    }

    // ------------------------------------------------------------------ get / file / list

    @Test
    void anAgentReadsOnlyTheirOwnInvoiceWithoutTheCostWorking() {
        Invoice mine = Invoice.builder().id("I1").agentId("A1").bookingId("B1").passThroughAmount(new BigDecimal("900")).build();
        Invoice theirs = Invoice.builder().id("I2").agentId("A2").build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(mine));
        when(invoiceRepository.findById("I2")).thenReturn(Optional.of(theirs));
        when(invoiceRepository.findById("I9")).thenReturn(Optional.empty());
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                line("L1", null, "D1", "1000.00"), line("L2", "B7", null, "10.00")));
        when(invoiceLineSource.bookingIdsOf(mine)).thenReturn(List.of("B1"));
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(booking("B1", 1)));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").referenceNumber("CONF1").netCost(new BigDecimal("800")).build()));
        signInAs("A1", UserType.AGENT);

        InvoiceResponse res = service.get("I1");

        assertThat(res.getPassThroughAmount()).isNull();
        assertThat(res.getAgentName()).isEqualTo("Liam");
        assertThat(res.getBookings()).singleElement().satisfies(b -> {
            assertThat(b.getBookingRef()).isEqualTo("BK00000001");
            assertThat(b.getSupplier()).isEqualTo("Agoda");
        });
        assertThat(res.getLines().get(0).getBookingId()).isEqualTo("B1");
        assertThat(res.getLines().get(0).getBookingRef()).isEqualTo("BK00000001");
        assertThat(res.getLines().get(0).getVoucherReference()).isEqualTo("CONF1");
        assertThat(res.getLines().get(0).getPurchaseCost()).isNull();
        assertThat(res.getLines().get(1).getBookingRef()).isNull();
        assertThat(res.getLines().get(1).getVoucherReference()).isNull();
        assertThatThrownBy(() -> service.get("I2")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.get("I9")).hasMessage("Invoice not found: I9");
    }

    @Test
    void anAccountantSeesTheVoucherCostAndAnInvoiceWithoutABookingHasNoAgentName() {
        Invoice inv = Invoice.builder().id("I1").agentId("A1").build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", null, "D1", "1000.00")));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").netCost(new BigDecimal("800")).build()));

        InvoiceResponse res = service.get("I1");

        assertThat(res.getAgentName()).isNull();
        assertThat(res.getLines().get(0).getBookingId()).isNull();
        assertThat(res.getLines().get(0).getPurchaseCost()).isEqualByComparingTo("800");
    }

    @Test
    void anUploadedFileIsServedAndAMissingOneRefused() {
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(Invoice.builder().id("I1").agentId("A1")
                .pdfFileKey("k1").uploadedFileName("inv.pdf").uploadedContentType("application/pdf").build()));
        when(invoiceRepository.findById("I2")).thenReturn(Optional.of(Invoice.builder().id("I2").agentId("A1").build()));
        ByteArrayResource file = new ByteArrayResource(new byte[]{1});
        when(fileStorageService.retrieve("k1")).thenReturn(file);

        InvoiceDocumentService.UploadedInvoiceFile f = service.getUploadedFile("I1");

        assertThat(f.resource()).isSameAs(file);
        assertThat(f.filename()).isEqualTo("inv.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> service.getUploadedFile("I2")).hasMessage("This invoice has no uploaded file");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgentsPagedListAppliesEveryFilterAndCarriesBookingRefs() {
        signInAs("A1", UserType.AGENT);
        Invoice i1 = Invoice.builder().id("I1").bookingId("B1").fxRateToInr(new BigDecimal("2"))
                .amountReceived(new BigDecimal("10")).creditNoteTotal(new BigDecimal("1")).build();
        Invoice i2 = Invoice.builder().id("I2").build();
        Pageable page = PageRequest.of(0, 20);
        when(invoiceRepository.findAll(any(Specification.class), eq(page))).thenReturn(new PageImpl<>(List.of(i1, i2), page, 2));
        InvoiceLineItem other = line("L1", "B2", null, "1");
        other.setInvoiceId("I1");
        InvoiceLineItem noBooking = line("L2", null, null, "1");
        noBooking.setInvoiceId("I2");
        when(invoiceLineItemRepository.findByInvoiceIdInOrderBySortOrderAsc(List.of("I1", "I2"))).thenReturn(List.of(other, noBooking));
        when(bookingRepository.findAllById(List.of("B1", "B2"))).thenReturn(List.of(booking("B1", 1), booking("B2", 2)));
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 10, 1);

        PagedResponse<InvoiceListItemResponse> res = (PagedResponse<InvoiceListItemResponse>)
                service.list(InvoiceLifecycle.ISSUED, "K1", "B1", from, to, page);

        assertThat(res.getContent()).extracting(InvoiceListItemResponse::getBookingRef).containsExactly("BK00000001, BK00000002", "");
        assertThat(res.getContent().get(0).getAmountReceivedInr()).isEqualByComparingTo("20.00");
        assertThat(res.getContent().get(0).getCreditNoteTotalInr()).isEqualByComparingTo("2.00");

        ArgumentCaptor<Specification<Invoice>> spec = ArgumentCaptor.forClass(Specification.class);
        verify(invoiceRepository).findAll(spec.capture(), eq(page));
        Root<Invoice> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(root, query, cb);
        verify(cb).equal(any(), eq("A1"));
        verify(cb).equal(any(), eq(InvoiceLifecycle.ISSUED));
        verify(cb).equal(any(), eq("K1"));
        verify(cb, org.mockito.Mockito.atLeastOnce()).equal(any(), eq("B1"));
        verify(cb).or(any(), any());
        verify(cb).greaterThanOrEqualTo(ArgumentMatchers.<Expression<LocalDate>>any(), eq(from));
        verify(cb).lessThanOrEqualTo(ArgumentMatchers.<Expression<LocalDate>>any(), eq(to));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnpagedListIsNewestFirstAndAnEmptyOneNeedsNoLookups() {
        Invoice i1 = Invoice.builder().id("I1").bookingId("B1").build();
        when(invoiceRepository.findAll(any(Specification.class), eq(Sort.by(Sort.Direction.DESC, "createdAt"))))
                .thenReturn(List.of(i1)).thenReturn(List.of());
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(booking("B1", 1)));

        List<InvoiceListItemResponse> res = (List<InvoiceListItemResponse>) service.list(null, null, null, null, null, null);
        List<InvoiceListItemResponse> none = (List<InvoiceListItemResponse>) service.list(null, null, null, null, null, null);

        assertThat(res).singleElement().satisfies(r -> assertThat(r.getBookingRef()).isEqualTo("BK00000001"));
        assertThat(none).isEmpty();
        verify(invoiceLineItemRepository).findByInvoiceIdInOrderBySortOrderAsc(any());
    }

    // ------------------------------------------------------------------ getPdf

    private Invoice printable(InvoiceServiceCategory category) {
        return Invoice.builder().id("I1").agentId("A1").bookingId("B1").invoiceNumber("ITI/26-27/0001").invoiceDate(TODAY)
                .status(InvoiceLifecycle.ISSUED).documentType(InvoiceDocumentType.TAX_INVOICE).serviceCategory(category)
                .clientId("K1").clientName("Asha").currencyCode("INR").grandTotal(new BigDecimal("1000"))
                .grandTotalInr(new BigDecimal("1000")).taxableValue(new BigDecimal("1000")).build();
    }

    private static boolean isPdf(byte[] bytes) {
        return new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("%PDF");
    }

    @Test
    void anAirInvoicePrintsWithTheLogoAndTheIssuingAgent() {
        Invoice inv = printable(InvoiceServiceCategory.AIR_DOMESTIC);
        inv.setIssuedBy("A1");
        InvoiceLineItem ticket = line("L1", "B1", "D1", "1000.00");
        ticket.setLegs(List.of(VoucherLeg.builder().from("BOM").to("DEL").airline("IndiGo").flightNumber("6E 1").build()));
        ticket.setTravellers(List.of(VoucherTraveller.builder().name("PAX A").build()));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(ticket));
        Tenant agency = agency();
        agency.setLogoKey("logo.png");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));
        when(fileStorageService.retrieve("logo.png")).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").referenceNumber("PNR1").details(Map.of("k", "v")).build()));
        when(agentRepository.findById("A1")).thenReturn(Optional.of(Agent.builder().id("A1").name("Liam").build()));

        assertThat(isPdf(service.getPdf("I1"))).isTrue();
        verify(fileStorageService).retrieve("logo.png");
    }

    @Test
    void aHotelInvoicePrintsEvenWhenTheLogoCannotBeReadAndNamesTheOwner() {
        Invoice inv = printable(InvoiceServiceCategory.HOTEL);
        inv.setCreatedBy("T1");
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", "B1", "D1", "1000.00")));
        Tenant agency = agency();
        agency.setLogoKey("logo.png");
        agency.setOwnerName("Dana");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));
        when(fileStorageService.retrieve("logo.png")).thenReturn(new InputStreamResource(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("gone");
            }
        }));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").referenceNumber("CONF1").build()));
        when(agentRepository.findById("T1")).thenReturn(Optional.empty());

        assertThat(isPdf(service.getPdf("I1"))).isTrue();
        verify(tenantRepository, org.mockito.Mockito.times(2)).findById("T1");
    }

    @Test
    void aHotelInvoiceWithNoAuthorAndABlankLogoStillPrints() {
        Invoice inv = printable(InvoiceServiceCategory.HOTEL);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", "B1", "D1", "1000.00")));
        Tenant agency = agency();
        agency.setLogoKey(" ");
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency));

        assertThat(isPdf(service.getPdf("I1"))).isTrue();
        agency.setLogoKey(null);
        assertThat(isPdf(service.getPdf("I1"))).isTrue();
        verify(fileStorageService, never()).retrieve(any());
        verify(agentRepository, never()).findById(any());
    }

    @Test
    void aGeneralInvoicePrintsTheCostItPassesThroughAndTheBookingRefs() {
        Invoice inv = printable(InvoiceServiceCategory.MISCELLANEOUS);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", "B1", "D1", "1000.00")));
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        Booking b1 = booking("B1", 1);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().id("C1").description("Hotel").netCost(new BigDecimal("800")).netCostInr(new BigDecimal("800")).build()));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").referenceNumber("REF1").build(), BookingDocument.builder().id("D2").referenceNumber(" ").build(),
                BookingDocument.builder().id("D3").build()));
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(b1));

        assertThat(isPdf(service.getPdf("I1"))).isTrue();
        verify(bookingCostComponentRepository).findByBookingIdOrderBySortOrder("B1");
    }

    @Test
    void aCombinedOrBooklessInvoicePrintsWithoutCostComponents() {
        Invoice combined = printable(InvoiceServiceCategory.MISCELLANEOUS);
        Invoice bookless = printable(null);
        bookless.setId("I2");
        bookless.setBookingId(null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(combined));
        when(invoiceRepository.findById("I2")).thenReturn(Optional.of(bookless));
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(agency()));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking("B1", 1)));
        when(invoiceLineSource.bookingIdsOf(combined)).thenReturn(List.of("B1", "B2"));

        assertThat(isPdf(service.getPdf("I1"))).isTrue();
        assertThat(isPdf(service.getPdf("I2"))).isTrue();
        verify(bookingCostComponentRepository, never()).findByBookingIdOrderBySortOrder(any());
    }

    // ------------------------------------------------------------------ issue

    private Invoice draft(InvoiceBillingModel model) {
        return Invoice.builder().id("I1").bookingId("B1").clientId("K1").status(InvoiceLifecycle.DRAFT)
                .documentType(InvoiceDocumentType.TAX_INVOICE).serviceCategory(InvoiceServiceCategory.HOTEL)
                .supplyNature(SupplyNature.values()[0]).billingModel(model).currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .build();
    }

    private void money(Invoice inv, String taxable, String gst, String tcs) {
        BigDecimal t = new BigDecimal(taxable);
        BigDecimal g = gst == null ? null : new BigDecimal(gst);
        BigDecimal c = tcs == null ? null : new BigDecimal(tcs);
        inv.setTaxableValue(t);
        inv.setTaxableValueInr(t);
        inv.setGstTotalInr(g);
        inv.setTcsAmountInr(c);
        BigDecimal grand = t.add(g == null ? BigDecimal.ZERO : g).add(c == null ? BigDecimal.ZERO : c);
        inv.setGrandTotal(grand);
        inv.setGrandTotalInr(grand);
    }

    /** Issues the invoice with one plain line and returns the journal posted, or null when none was. */
    private JournalPosting issue(Invoice inv) {
        when(invoiceRepository.findById(inv.getId())).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(inv.getId())).thenReturn(List.of(line("L1", null, null, "1")));
        when(documentNumberService.next(any(), eq(TODAY))).thenReturn("HTL/26-27/0001");
        service.issue(inv.getId());
        ArgumentCaptor<JournalPosting> posted = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService, org.mockito.Mockito.atMost(1)).post(posted.capture());
        return posted.getAllValues().isEmpty() ? null : posted.getValue();
    }

    private static Map<String, BigDecimal> credits(JournalPosting p) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        p.lines().stream().skip(1).forEach(l -> out.put(l.accountCode(), l.creditAmount()));
        return out;
    }

    @Test
    void issuingNeedsTheNatureOfSupply() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        inv.setSupplyNature(null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", null, null, "1")));

        assertThatThrownBy(() -> service.issue("I1")).hasMessage("Choose the nature of supply before generating the invoice");
        verify(documentNumberService, never()).next(any(), any());
    }

    @Test
    void aCommissionAgentInvoicePostsPassThroughFeeAndGstOnTheFee() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(inv, "10000.00", "90.00", null);
        inv.setPassThroughAmount(new BigDecimal("9500.00"));

        JournalPosting p = issue(inv);

        assertThat(p.purpose()).isEqualTo(JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT);
        assertThat(p.lines().get(0).accountCode()).isEqualTo(SystemAccount.ACCOUNTS_RECEIVABLE.code());
        assertThat(p.lines().get(0).debitAmount()).isEqualByComparingTo("10090.00");
        assertThat(credits(p)).containsOnlyKeys(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code(),
                SystemAccount.SERVICE_FEE_INCOME.code(), SystemAccount.OUTPUT_GST_PAYABLE.code());
        assertThat(credits(p).get(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code())).isEqualByComparingTo("9500.00");
        assertThat(credits(p).get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("500.00");
        assertThat(credits(p).get(SystemAccount.OUTPUT_GST_PAYABLE.code())).isEqualByComparingTo("90.00");
        assertThat(inv.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(inv.getInvoiceNumber()).isEqualTo("HTL/26-27/0001");
        assertThat(inv.getIssuedBy()).isEqualTo("AC1");
        ArgumentCaptor<LedgerPosting> ledger = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(LedgerEntryType.INVOICE_RAISED);
        verify(paymentReceiptService).applyAvailableWallet(inv);
    }

    @Test
    void aCommissionAgentInvoiceWithNoFeePostsOnlyThePassThrough() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(inv, "1000.00", null, null);
        inv.setPassThroughAmount(new BigDecimal("1000.00"));

        JournalPosting p = issue(inv);

        assertThat(credits(p)).containsOnlyKeys(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code());
    }

    @Test
    void aCommissionAgentInvoiceNeverPostsANegativePassThrough() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        inv.setBookingId(null);
        inv.setFxRateToInr(new BigDecimal("2"));
        inv.setTaxableValue(new BigDecimal("100.00"));
        inv.setTaxableValueInr(new BigDecimal("150.00"));
        inv.setGrandTotalInr(new BigDecimal("200.00"));

        JournalPosting p = issue(inv);

        // fee 100 x 2 = 200 is more than the taxable 150: nothing passes through.
        assertThat(credits(p)).containsOnlyKeys(SystemAccount.SERVICE_FEE_INCOME.code());
        assertThat(credits(p).get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("200.00");
    }

    @Test
    void aCommissionAgentFeeComesFromTheCostComponentsWhenThereAreAny() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(inv, "12000.00", null, null);
        Booking b1 = booking("B1", 1);
        b1.setSellingPrice(new BigDecimal("12000.00"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().netCostInr(new BigDecimal("7000.00")).build(),
                BookingCostComponent.builder().netCostInr(new BigDecimal("3000.00")).build()));

        JournalPosting p = issue(inv);

        assertThat(credits(p).get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("2000.00");
        assertThat(credits(p).get(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code())).isEqualByComparingTo("10000.00");
    }

    @Test
    void costComponentsAboveAnUnpricedBookingLeaveNoFee() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(inv, "500.00", null, null);
        Booking b1 = booking("B1", 1);
        b1.setSellingPrice(null);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(
                BookingCostComponent.builder().netCostInr(new BigDecimal("300.00")).build()));

        JournalPosting p = issue(inv);

        assertThat(credits(p)).containsOnlyKeys(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code());
    }

    @Test
    void aCommissionAgentInvoiceForAMissingOrUncostedBookingTaxesTheWholePackage() {
        Invoice missing = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(missing, "800.00", null, null);
        when(bookingRepository.findById("B1")).thenReturn(Optional.empty());

        JournalPosting p = issue(missing);

        assertThat(credits(p)).containsOnlyKeys(SystemAccount.SERVICE_FEE_INCOME.code());
        assertThat(credits(p).get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("800.00");
    }

    @Test
    void anUncostedBookingTaxesTheWholePackage() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(inv, "800.00", null, null);
        Booking noCost = booking("B1", 1);
        noCost.setNetCost(null);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(noCost));

        JournalPosting p = issue(inv);

        assertThat(credits(p).get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("800.00");
    }

    @Test
    void aForeignInvoiceWithNoRateIsCheckedAgainstItsVouchersUnconverted() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        inv.setCurrencyCode("USD");
        inv.setFxRateToInr(null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        InvoiceLineItem own = line("L1", "B1", "D1", "1000.00");
        own.setTravellers(List.of(VoucherTraveller.builder().name("PAX A").build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(own));
        Booking b1 = booking("B1", 1);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.HOTEL))
                .thenReturn(new InvoiceLineSource.Lines(List.of(lineReq("B1", "D1", "x", "999.00")), BigDecimal.ZERO));

        assertThatThrownBy(() -> service.issue("I1")).hasMessageContaining("vouchers changed");
    }

    @Test
    void anOtherVoucherOnAFlightNeedsNeitherFareNorTravellers() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "100.00", null, null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", "B1", "D1", "100.00")));
        Booking flight = booking("B1", 1);
        flight.setType(BookingType.FLIGHT);
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(flight));
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.OTHER).build()));
        when(documentNumberService.next(any(), eq(TODAY))).thenReturn("HTL/26-27/0001");

        assertThat(service.issue("I1").getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
    }

    @Test
    void aLaterVoucherInvoiceCarriesOnlyTheCostEarlierInvoicesDidNotCover() {
        Invoice inv = draft(InvoiceBillingModel.COMMISSION_AGENT);
        money(inv, "3000.00", null, null);
        Booking b1 = booking("B1", 1);
        b1.setNetCost(new BigDecimal("5000.00"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        Invoice draftOther = Invoice.builder().id("I2").status(InvoiceLifecycle.DRAFT).billingModel(InvoiceBillingModel.COMMISSION_AGENT).build();
        Invoice principal = Invoice.builder().id("I3").status(InvoiceLifecycle.ISSUED).billingModel(InvoiceBillingModel.PRINCIPAL).build();
        Invoice unposted = Invoice.builder().id("I4").status(InvoiceLifecycle.ISSUED).billingModel(InvoiceBillingModel.COMMISSION_AGENT).build();
        Invoice posted = Invoice.builder().id("I5").status(InvoiceLifecycle.PAID).billingModel(InvoiceBillingModel.COMMISSION_AGENT)
                .taxableValueInr(new BigDecimal("4500.00")).build();
        Invoice postedNoValue = Invoice.builder().id("I6").status(InvoiceLifecycle.ISSUED).billingModel(InvoiceBillingModel.COMMISSION_AGENT)
                .taxableValueInr(null).build();
        when(invoiceRepository.findByBookingIdAndDocumentTypeAndStatusNotOrderByCreatedAtAsc("B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.CANCELLED))
                .thenReturn(List.of(inv, draftOther, principal, unposted, posted, postedNoValue));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I4", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I5", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE5").build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I6", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE6").build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE5")).thenReturn(List.of(
                JournalLine.builder().accountCode(SystemAccount.SERVICE_FEE_INCOME.code()).creditAmountInr(new BigDecimal("500.00")).build(),
                JournalLine.builder().accountCode(SystemAccount.SERVICE_FEE_INCOME.code()).creditAmountInr(null).build(),
                JournalLine.builder().accountCode(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code()).creditAmountInr(new BigDecimal("4000.00")).build()));
        when(journalLineRepository.findByJournalEntryIdOrderByLineNo("JE6")).thenReturn(List.of());

        JournalPosting p = issue(inv);

        // Earlier invoices covered 4500 - 500 = 4000 of the 5000 cost; this one passes 1000 through, its fee is 2000.
        assertThat(credits(p).get(SystemAccount.SERVICE_FEE_INCOME.code())).isEqualByComparingTo("2000.00");
        assertThat(credits(p).get(SystemAccount.CLIENT_PASS_THROUGH_PAYABLE.code())).isEqualByComparingTo("1000.00");
    }

    @Test
    void aPrincipalInvoiceForAFutureDepartureIsDeferredWithGstAndTcs() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "10000.00", "500.00", "200.00");
        Booking b1 = booking("B1", 1);
        b1.setDepartureDate(TODAY.plusDays(30));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));

        JournalPosting p = issue(inv);

        assertThat(p.purpose()).isEqualTo(JournalPurpose.INVOICE_RAISED_DEFERRED);
        assertThat(credits(p)).containsOnlyKeys(SystemAccount.UNEARNED_TOUR_REVENUE.code(),
                SystemAccount.OUTPUT_GST_PAYABLE.code(), SystemAccount.TCS_PAYABLE.code());
        assertThat(credits(p).get(SystemAccount.TCS_PAYABLE.code())).isEqualByComparingTo("200.00");
        verify(bookingAccountingSync).syncPayment("B1");
    }

    @Test
    void aPrincipalInvoiceOfSeveralBookingsIsRecognisedOnIssue() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        inv.setServiceCategory(null);
        money(inv, "1000.00", null, null);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1", "B2"));

        JournalPosting p = issue(inv);

        assertThat(p.purpose()).isEqualTo(JournalPurpose.INVOICE_RAISED_RECOGNIZED);
        assertThat(credits(p)).containsOnlyKeys(SystemAccount.salesCode(InvoiceServiceCategory.MISCELLANEOUS));
        verify(documentNumberService).next(DocumentKind.TAX_INVOICE, TODAY);
        verify(invoiceRepository, never()).latestIssuedDate(any(), any());
    }

    @Test
    void aPrincipalInvoiceWithNoBookingOrNoDepartureIsRecognised() {
        Invoice bookless = draft(InvoiceBillingModel.PRINCIPAL);
        bookless.setBookingId(null);
        money(bookless, "1000.00", "0", "0");

        JournalPosting p = issue(bookless);

        assertThat(p.purpose()).isEqualTo(JournalPurpose.INVOICE_RAISED_RECOGNIZED);
        assertThat(credits(p)).containsOnlyKeys(SystemAccount.salesCode(InvoiceServiceCategory.HOTEL));
    }

    @Test
    void aPrincipalInvoiceWhoseBookingHasNoDepartureIsRecognised() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "1000.00", null, null);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking("B1", 1)));

        assertThat(issue(inv).purpose()).isEqualTo(JournalPurpose.INVOICE_RAISED_RECOGNIZED);
    }

    @Test
    void aPrincipalInvoiceWhoseBookingDepartedIsRecognised() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "1000.00", null, null);
        Booking b1 = booking("B1", 1);
        b1.setDepartureDate(TODAY.minusDays(1));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));

        assertThat(issue(inv).purpose()).isEqualTo(JournalPurpose.INVOICE_RAISED_RECOGNIZED);
    }

    @Test
    void aZeroValueInvoicePostsNoJournal() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "0.00", null, null);

        assertThat(issue(inv)).isNull();
        verify(customerLedgerService).post(any());
    }

    @Test
    void aLongNarrationIsCutTo255AndNamesBookingsAndVouchers() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "1000.00", null, null);
        List<String> ids = new ArrayList<>();
        List<Booking> bookings = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            ids.add("B" + i);
            bookings.add(booking("B" + i, i));
        }
        Booking unnumbered = booking("BX", 0);
        unnumbered.setBookingNumber(null);
        bookings.add(unnumbered);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(ids);
        when(bookingRepository.findAllById(ids)).thenReturn(bookings);

        JournalPosting p = issue(inv);

        assertThat(p.narration()).hasSize(255).startsWith("Tax invoice HTL/26-27/0001 raised · BK00000001, BK00000002").endsWith("…");
    }

    @Test
    void theNarrationNamesTheVoucherReferences() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "1000.00", null, null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        InvoiceLineItem l1 = line("L1", "B1", "D1", "1000.00");
        l1.setTravellers(List.of(VoucherTraveller.builder().name("PAX A").build()));
        InvoiceLineItem l2 = line("L2", "B1", "D2", "0.00");
        InvoiceLineItem l3 = line("L3", "B1", "D3", "0.00");
        l3.setTravellers(List.of(VoucherTraveller.builder().name("PAX C").build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(l1, l2, l3));
        when(documentNumberService.next(any(), eq(TODAY))).thenReturn("HTL/26-27/0001");
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of());
        when(bookingDocumentRepository.findAllById(List.of("D1", "D2", "D3"))).thenReturn(List.of(
                BookingDocument.builder().id("D1").referenceNumber("CONF1").build(),
                BookingDocument.builder().id("D2").referenceNumber(" ").build(),
                BookingDocument.builder().id("D3").build()));
        when(bookingDocumentRepository.findAllById(List.of("D2"))).thenReturn(List.of(BookingDocument.builder().id("D2").build()));

        service.issue("I1");

        ArgumentCaptor<JournalPosting> posted = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(posted.capture());
        assertThat(posted.getValue().narration()).isEqualTo("Tax invoice HTL/26-27/0001 raised · CONF1");
    }

    @Test
    void anInvoiceWhoseVouchersChangedIsNotIssuedButAGoneBookingIsSkipped() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        InvoiceLineItem own = line("L1", null, "D1", "1000.00");
        own.setTravellers(List.of(VoucherTraveller.builder().name("PAX A").build()));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(own));
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("BGONE", "B1"));
        when(bookingRepository.findById("BGONE")).thenReturn(Optional.empty());
        Booking b1 = booking("B1", 1);
        b1.setType(null);
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(b1));
        inv.setServiceCategory(null);
        when(invoiceLineSource.linesFor(b1, InvoiceServiceCategory.MISCELLANEOUS))
                .thenReturn(new InvoiceLineSource.Lines(List.of(lineReq("B1", "D1", "x", "1200.00")), BigDecimal.ZERO));

        assertThatThrownBy(() -> service.issue("I1"))
                .hasMessage("BK00000001's vouchers changed since this draft was saved - save the draft again to bring it up to date");
    }

    @Test
    void aFlightTicketNeedsItsFareBreakdownAndTravellers() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        inv.setServiceCategory(InvoiceServiceCategory.AIR_DOMESTIC);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        InvoiceLineItem ticket = line("L1", "B1", "D1", "1000.00");
        ticket.setTravellers(List.of());
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(ticket));
        Booking flight = booking("B1", 1);
        flight.setType(BookingType.FLIGHT);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of("B1"));
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(flight));
        when(invoiceLineSource.linesFor(flight, InvoiceServiceCategory.AIR_DOMESTIC))
                .thenReturn(new InvoiceLineSource.Lines(List.of(lineReq("B1", "D1", "x", "1000.00")), BigDecimal.ZERO));
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(flight));
        BookingDocument noRef = BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET).build();
        BookingDocument withRef = BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET)
                .referenceNumber("PNR9").charges(Map.of("base", BigDecimal.TEN)).build();
        when(bookingDocumentRepository.findAllById(List.of("D1"))).thenReturn(List.of(noRef), List.of(withRef), List.of(withRef),
                List.of(BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET).charges(Map.of()).build()));

        assertThatThrownBy(() -> service.issue("I1")).hasMessage("Add the fare breakdown for the flight voucher before generating the invoice");
        assertThatThrownBy(() -> service.issue("I1"))
                .hasMessage("Add the travellers for PNR PNR9 on the booking's voucher - delete this draft first, then draft it again");
        assertThatThrownBy(() -> service.issue("I1"))
                .hasMessage("Add the travellers for the air ticket on the booking's voucher - delete this draft first, then draft it again");
    }

    @Test
    void aFlightTicketWithAReferenceButNoFareNamesThePnr() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        InvoiceLineItem ticket = line("L1", "B1", "D1", "1000.00");
        InvoiceLineItem hotel = line("L2", "B1", "D2", "0.00");
        ticket.setTravellers(List.of());
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(ticket, hotel));
        Booking flight = booking("B1", 1);
        flight.setType(BookingType.FLIGHT);
        when(invoiceLineSource.bookingIdsOf(inv)).thenReturn(List.of());
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(flight));
        when(bookingDocumentRepository.findAllById(List.of("D1", "D2"))).thenReturn(List.of(
                BookingDocument.builder().id("D2").bookingId("B1").docType(BookingDocumentType.OTHER).build(),
                BookingDocument.builder().id("D1").bookingId("B1").docType(BookingDocumentType.E_TICKET).referenceNumber("PNR1").build()));

        assertThatThrownBy(() -> service.issue("I1")).hasMessage("Add the fare breakdown for PNR PNR1 before generating the invoice");
    }

    @Test
    void anInvoiceIsNotDatedAheadOrBeforeTheLastInItsSeries() {
        Invoice ahead = draft(InvoiceBillingModel.PRINCIPAL);
        ahead.setInvoiceDate(TODAY.plusDays(2));
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(ahead));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(any())).thenReturn(List.of(line("L1", null, null, "1")));
        Invoice early = draft(InvoiceBillingModel.PRINCIPAL);
        early.setId("I2");
        early.setInvoiceDate(TODAY);
        when(invoiceRepository.findById("I2")).thenReturn(Optional.of(early));
        when(invoiceRepository.latestIssuedDate(InvoiceServiceCategory.HOTEL, FinancialYear.of(TODAY))).thenReturn(TODAY.plusDays(1));

        assertThatThrownBy(() -> service.issue("I1")).hasMessage("The invoice date can't be in the future");
        assertThatThrownBy(() -> service.issue("I2")).hasMessageStartingWith("Hotel Invoices are already issued up to ");
        verify(documentNumberService, never()).next(any(), any());
    }

    @Test
    void anInvoiceDatedOnTheLastIssuedDateIsAllowed() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        money(inv, "100.00", null, null);
        inv.setInvoiceDate(TODAY);
        when(invoiceRepository.latestIssuedDate(InvoiceServiceCategory.HOTEL, FinancialYear.of(TODAY))).thenReturn(TODAY);

        assertThat(issue(inv)).isNotNull();
        assertThat(inv.getInvoiceDate()).isEqualTo(TODAY);
    }

    // ------------------------------------------------------------------ convert / cancel

    @Test
    void convertingAProformaWithoutAdvancesCopiesItsTaxes() {
        Invoice proforma = draft(InvoiceBillingModel.PRINCIPAL);
        proforma.setStatus(InvoiceLifecycle.PROFORMA_ISSUED);
        proforma.setDocumentType(InvoiceDocumentType.PROFORMA);
        proforma.setInvoiceNumber("PF-1");
        money(proforma, "1000.00", "50.00", null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proforma));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(line("L1", null, null, "1000.00")));
        when(invoiceTaxRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceTax.builder().id("T1").invoiceId("I1").label("GST").amount(new BigDecimal("50.00")).build()));
        when(documentNumberService.next(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.HOTEL), TODAY)).thenReturn("HTL/26-27/0002");
        when(paymentReceiptRepository.findByInvoiceIdAndIsAdvanceTrue("I1")).thenReturn(List.of());

        InvoiceResponse res = service.convertToTaxInvoice("I1");

        assertThat(res.getTaxes()).singleElement().satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("50.00"));
        assertThat(res.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(res.getBalanceDue()).isEqualByComparingTo("1050.00");
        assertThat(proforma.getStatus()).isEqualTo(InvoiceLifecycle.CANCELLED);
        assertThat(proforma.getCancelReason()).isEqualTo("Converted to HTL/26-27/0002");
        verify(paymentReceiptRepository, never()).saveAll(any());
    }

    @Test
    void cancellingAProformaPostsNoLedgerReversal() {
        Invoice proforma = draft(InvoiceBillingModel.PRINCIPAL);
        proforma.setStatus(InvoiceLifecycle.PROFORMA_ISSUED);
        proforma.setDocumentType(InvoiceDocumentType.PROFORMA);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proforma));

        InvoiceResponse res = service.cancel("I1", "client changed plans");

        assertThat(res.getStatus()).isEqualTo(InvoiceLifecycle.CANCELLED);
        verify(customerLedgerService, never()).post(any());
        verify(journalService, never()).reverse(any(), any());
    }

    @Test
    void cancellingAnIssuedInvoiceReversesTheJournalItPosted() {
        Invoice inv = draft(InvoiceBillingModel.PRINCIPAL);
        inv.setStatus(InvoiceLifecycle.ISSUED);
        inv.setInvoiceNumber("HTL/26-27/0001");
        money(inv, "1000.00", null, null);
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(inv));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_DEFERRED))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_RECOGNIZED))
                .thenReturn(Optional.of(JournalEntry.builder().id("JE1").build()));
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(JournalSourceType.INVOICE, "I1", JournalPurpose.INVOICE_RAISED_COMMISSION_AGENT))
                .thenReturn(Optional.empty());

        service.cancel("I1", "duplicate");

        verify(journalService).reverse("JE1", "Invoice HTL/26-27/0001 cancelled: duplicate");
        ArgumentCaptor<LedgerPosting> ledger = ArgumentCaptor.forClass(LedgerPosting.class);
        verify(customerLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(LedgerEntryType.REVERSAL);
        assertThat(inv.getBalanceDue()).isEqualByComparingTo("0");
    }

    @Test
    void theRetiredProformaIssueAlwaysRefuses() {
        assertThatThrownBy(() -> service.issueProforma("I1")).hasMessageStartingWith("Proforma has been retired");
    }

    @Test
    void theConversionPaisaIsLeftAloneWhenTheJournalAlreadyBalances() {
        List<JournalLinePosting> lines = List.of(
                JournalLinePosting.debitParty("1200", "CLIENT", "K1", new BigDecimal("100.00"), "n"),
                JournalLinePosting.credit("4010", new BigDecimal("100.00"), "n"));

        assertThat(InvoiceDocumentService.withConversionPaisa(lines)).isSameAs(lines);
    }
}
