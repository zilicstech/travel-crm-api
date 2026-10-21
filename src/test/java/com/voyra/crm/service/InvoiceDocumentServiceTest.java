package com.voyra.crm.service;

import com.voyra.crm.dto.InvoiceDraftRequest;
import com.voyra.crm.dto.InvoiceLineItemRequest;
import com.voyra.crm.dto.InvoiceResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.BookingPassenger;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.entity.InvoiceLineItem;
import com.voyra.crm.entity.PaymentReceipt;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.InvoiceServiceCategory;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.ReceiptDirection;
import com.voyra.crm.enums.SupplyNature;
import com.voyra.crm.enums.TaxTreatment;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingPassengerRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.BookingSectorRepository;
import com.voyra.crm.repository.InvoiceLineItemRepository;
import com.voyra.crm.repository.InvoiceRepository;
import com.voyra.crm.repository.PaymentReceiptRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceDocumentServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceLineItemRepository invoiceLineItemRepository;
    @Mock
    private com.voyra.crm.repository.InvoiceTaxRepository invoiceTaxRepository;
    @Mock
    private PaymentReceiptRepository paymentReceiptRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingPassengerRepository bookingPassengerRepository;
    @Mock
    private BookingSectorRepository bookingSectorRepository;
    @Mock
    private com.voyra.crm.repository.BookingCostComponentRepository bookingCostComponentRepository;
    @Mock
    private JournalService journalService;
    @Mock
    private com.voyra.crm.repository.JournalEntryRepository journalEntryRepository;
    @Mock
    private ClientService clientService;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private com.voyra.crm.repository.TaxRateConfigRepository taxRateConfigRepository;
    @Mock
    private TaxEngine taxEngine;
    @Mock
    private DocumentNumberService documentNumberService;
    @Mock
    private AuditService auditService;
    @Mock
    private CustomerLedgerService customerLedgerService;
    @Mock
    private BookingAccountingSync bookingAccountingSync;
    @Mock
    private PaymentReceiptService paymentReceiptService;
    @Mock
    private FileStorageService fileStorageService;

    @org.mockito.Spy

    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);
    @Mock
    private com.voyra.crm.repository.BookingDocumentRepository bookingDocumentRepository;



    @InjectMocks
    private InvoiceDocumentService invoiceDocumentService;

    @BeforeEach
    void authenticateAsAccountant() {
        TestInvoiceLines.into(invoiceDocumentService, invoiceRepository, invoiceLineItemRepository, bookingDocumentRepository);
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        org.mockito.Mockito.lenient().when(tenantRepository.findById("T1")).thenReturn(Optional.of(
                Tenant.builder().id("T1").agencyName("Global Explorer").legalName("Global Explorer Pvt Ltd")
                        .gstNumber("27AAAAA0000A1Z5").stateCode("27").address("Mumbai").build()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Booking booking() {
        return Booking.builder().id("B1").clientId("K1").clientName("Arjun Mehta").agentId("A1").agentName("Liam")
                .type(BookingType.PACKAGE).destination("Dubai").bookingStatus(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PENDING).build();
    }

    private Client client() {
        return Client.builder().id("K1").name("Arjun Mehta").stateCode("27").build();
    }

    private InvoiceLineItemRequest lineRequest() {
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setDescription("Dubai package");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("50000.00"));
        return line;
    }

    /** EUR 103.04 at 90.5: the converted grand total is 9325.12 but taxable 9050.91 + GST 274.22 is 9325.13. */
    @Test
    void aForeignInvoicesConversionPaisaIsAbsorbedSoTheJournalBalances() {
        List<com.voyra.crm.models.JournalLinePosting> lines = List.of(
                com.voyra.crm.models.JournalLinePosting.debitParty("1200", "CLIENT", "K1", new BigDecimal("9325.12"), "n"),
                com.voyra.crm.models.JournalLinePosting.credit("4010", new BigDecimal("9050.91"), "n"),
                com.voyra.crm.models.JournalLinePosting.credit("2300", new BigDecimal("274.22"), "n"));

        List<com.voyra.crm.models.JournalLinePosting> fixed = InvoiceDocumentService.withConversionPaisa(lines);

        assertThat(fixed.get(1).creditAmount()).isEqualByComparingTo("9050.90");
        assertThat(fixed.get(2).creditAmount()).isEqualByComparingTo("274.22");
        // A real error is not papered over.
        List<com.voyra.crm.models.JournalLinePosting> wrong = List.of(lines.get(0),
                com.voyra.crm.models.JournalLinePosting.credit("4010", new BigDecimal("9000.00"), "n"));
        assertThat(InvoiceDocumentService.withConversionPaisa(wrong)).isSameAs(wrong);
    }

    @Test
    void addingNoTaxLeavesTheGrandTotalAtTheLineTotalAlone() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setLines(List.of(lineRequest()));
        // taxes left null entirely - the most common case now, not just an empty list.

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getTaxes()).isEmpty();
        assertThat(response.getGrandTotal()).isEqualByComparingTo("50000.00");
        assertThat(response.getPlaceOfSupplyCode()).isNull();
        assertThat(response.getTaxTreatment()).isNull();
    }

    @Test
    void addingAGstTaxSplitsIntraStateIntoCgstAndSgst() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);
        when(invoiceTaxRepository.existsById(any())).thenReturn(false);
        when(taxRateConfigRepository.findById("TC1")).thenReturn(Optional.of(
                com.voyra.crm.entity.TaxRateConfig.builder().id("TC1").taxKind(com.voyra.crm.enums.TaxKind.GST)
                        .label("GST 5%").supplyNature(SupplyNature.DOMESTIC_PACKAGE).ratePercent(new BigDecimal("5.000")).build()));
        when(taxEngine.resolveTreatment(eq("K1"), any(), anyBoolean(), any())).thenReturn(TaxTreatment.INTRA_STATE);
        when(taxEngine.resolvePlaceOfSupplyCode(eq("K1"), any())).thenReturn("27");

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setLines(List.of(lineRequest()));
        com.voyra.crm.dto.InvoiceTaxRequest tax = new com.voyra.crm.dto.InvoiceTaxRequest();
        tax.setLabel("GST 5%");
        tax.setTaxRateConfigId("TC1");
        tax.setMode(com.voyra.crm.enums.TaxLineMode.PERCENT);
        tax.setRatePercent(new BigDecimal("5.000"));
        request.setTaxes(List.of(tax));

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getTaxes()).hasSize(1);
        assertThat(response.getCgstAmount()).isEqualByComparingTo("1250.00");
        assertThat(response.getSgstAmount()).isEqualByComparingTo("1250.00");
        assertThat(response.getGstTotal()).isEqualByComparingTo("2500.00");
        assertThat(response.getGrandTotal()).isEqualByComparingTo("52500.00");
    }

    @Test
    void commissionAgentInvoiceTaxesOnlyTheFeeNotTheFullPackage() {
        // Air booking -> InvoiceServiceCategory.AIR_INTERNATIONAL -> defaults to COMMISSION_AGENT
        // (Decision 8). sellingPrice 50000, cost components total 40000 -> fee is 10000, and GST
        // must be computed off that 10000, not the 50000 the line item/taxableValue carries.
        Booking flightBooking = Booking.builder().id("B1").clientId("K1").clientName("Arjun Mehta")
                .agentId("A1").agentName("Liam").type(BookingType.FLIGHT).internationalTrip(true)
                .destination("Addis Ababa").bookingStatus(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PENDING).sellingPrice(new BigDecimal("50000.00")).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(flightBooking));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);
        when(invoiceTaxRepository.existsById(any())).thenReturn(false);
        when(taxRateConfigRepository.findById("TC1")).thenReturn(Optional.of(
                com.voyra.crm.entity.TaxRateConfig.builder().id("TC1").taxKind(com.voyra.crm.enums.TaxKind.GST)
                        .label("GST 18%").supplyNature(SupplyNature.DOMESTIC_PACKAGE).ratePercent(new BigDecimal("18.000")).build()));
        when(taxEngine.resolveTreatment(eq("K1"), any(), anyBoolean(), any())).thenReturn(TaxTreatment.INTRA_STATE);
        when(taxEngine.resolvePlaceOfSupplyCode(eq("K1"), any())).thenReturn("27");
        com.voyra.crm.entity.BookingCostComponent component = com.voyra.crm.entity.BookingCostComponent.builder()
                .id("BCC1").bookingId("B1").vendorName("Ethiopian Airlines")
                .netCost(new BigDecimal("40000.00")).netCostInr(new BigDecimal("40000.00")).build();
        when(bookingCostComponentRepository.findByBookingIdOrderBySortOrder("B1")).thenReturn(List.of(component));

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        InvoiceLineItemRequest line = new InvoiceLineItemRequest();
        line.setDescription("Addis Ababa air ticket");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("50000.00"));
        request.setLines(List.of(line));
        com.voyra.crm.dto.InvoiceTaxRequest tax = new com.voyra.crm.dto.InvoiceTaxRequest();
        tax.setLabel("GST 18%");
        tax.setTaxRateConfigId("TC1");
        tax.setMode(com.voyra.crm.enums.TaxLineMode.PERCENT);
        tax.setRatePercent(new BigDecimal("18.000"));
        request.setTaxes(List.of(tax));

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getBillingModel()).isEqualTo(com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT);
        assertThat(response.getTaxableValue()).isEqualByComparingTo("50000.00");
        // GST is 18% of the 10000 fee (1800), split 900/900 intra-state - not 18% of 50000 (9000).
        assertThat(response.getCgstAmount()).isEqualByComparingTo("900.00");
        assertThat(response.getSgstAmount()).isEqualByComparingTo("900.00");
        assertThat(response.getGstTotal()).isEqualByComparingTo("1800.00");
        // The client is still billed the whole ticket: 50000 + 1800 GST, not just the 10000 fee.
        assertThat(response.getGrandTotal()).isEqualByComparingTo("51800.00");
        assertThat(response.getTaxableValueInr()).isEqualByComparingTo("50000.00");
    }

    @Test
    void createDraftRejectsACancelledBooking() {
        Booking cancelled = booking().toBuilder().bookingStatus(BookingStatus.CANCELLED).build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(cancelled));

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        assertThatThrownBy(() -> invoiceDocumentService.createDraft(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelled booking");
    }

    @Test
    void createDraftRejectsASecondDraftOnTheSameBooking() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(
                "B1", InvoiceDocumentType.TAX_INVOICE, InvoiceLifecycle.DRAFT)).thenReturn(true);

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        assertThatThrownBy(() -> invoiceDocumentService.createDraft(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Finish or cancel the existing draft");
    }

    @Test
    void nonInrCurrencyRequiresAPositiveFxRate() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);
        request.setCurrencyCode("USD");

        assertThatThrownBy(() -> invoiceDocumentService.createDraft(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enter the rate: what 1 USD is in");
    }

    @Test
    void createDraftComputesTotalsFromEachLineAndLocksNothingYet() {
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(booking()));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);
        request.setLines(List.of(lineRequest()));
        // No taxes requested - tax is opt-in now, so the grand total is the line total alone.

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.DRAFT);
        assertThat(response.getInvoiceNumber()).isNull();
        assertThat(response.getGrandTotal()).isEqualByComparingTo("50000.00");
        assertThat(response.getGrandTotalInr()).isEqualByComparingTo("50000.00");
        assertThat(response.getFxRateToInr()).isEqualByComparingTo("1");
        assertThat(response.getFxRateSource()).isEqualTo(FxRateSource.INR_IDENTITY);
    }

    @Test
    void createDraftWithNoLinesDerivesThemFromBookingPassengers() {
        Booking flightBooking = Booking.builder().id("B1").clientId("K1").clientName("Arjun Mehta")
                .agentId("A1").agentName("Liam").type(BookingType.FLIGHT).internationalTrip(true)
                .destination("Addis Ababa").bookingStatus(BookingStatus.CONFIRMED)
                .paymentStatus(PaymentStatus.PENDING).pnr("LVBJAY").build();
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(flightBooking));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);
        BookingPassenger passenger = BookingPassenger.builder().id("BP1").bookingId("B1")
                .passengerName("ABHISHEK KUMAR SINGH").fareAmount(new BigDecimal("138996.00")).build();
        when(bookingPassengerRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(passenger));
        when(bookingSectorRepository.findByBookingPassengerIdInOrderBySortOrderAsc(List.of("BP1"))).thenReturn(List.of());

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);
        // No lines set - must be derived from the booking's captured passenger.

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getServiceCategory()).isEqualTo(InvoiceServiceCategory.AIR_INTERNATIONAL);
        assertThat(response.getLines()).hasSize(1);
        assertThat(response.getLines().get(0).getUnitPrice()).isEqualByComparingTo("138996.00");
        assertThat(response.getLines().get(0).getDescription()).contains("ABHISHEK KUMAR SINGH").contains("Pnr: LVBJAY");
    }

    @Test
    void anExplicitServiceCategoryOverridesWhatTheBookingWouldDerive() {
        // MISCELLANEOUS and RAIL have no BookingType counterpart - the request's own
        // serviceCategory is the only way to reach them (ACCOUNTING_REDESIGN_SPEC.md gap 8).
        Booking packageBooking = booking(); // type PACKAGE -> would derive InvoiceServiceCategory.PACKAGE
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(packageBooking));
        when(invoiceRepository.existsByBookingIdAndDocumentTypeAndStatus(any(), any(), any())).thenReturn(false);
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setBookingId("B1");
        request.setSupplyNature(SupplyNature.OTHER);
        request.setServiceCategory(com.voyra.crm.enums.InvoiceServiceCategory.MISCELLANEOUS);

        InvoiceResponse response = invoiceDocumentService.createDraft(request);

        assertThat(response.getServiceCategory()).isEqualTo(com.voyra.crm.enums.InvoiceServiceCategory.MISCELLANEOUS);
    }

    @Test
    void issuingAUsdInvoiceStoresGrandTotalInrAtTheRateStampedAtIssue() {
        Invoice draft = Invoice.builder()
                .id("I1").documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.DRAFT)
                .clientId("K1").clientName("Arjun Mehta").agentId("A1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("USD").fxRateToInr(new BigDecimal("83.120000"))
                .grandTotal(new BigDecimal("1000.00")).grandTotalInr(new BigDecimal("83120.00"))
                .build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().id("L1").invoiceId("I1").description("x").build()));
        when(documentNumberService.next(DocumentKind.TAX_INVOICE, LocalDate.now())).thenReturn("INV/2026-27/0001");

        InvoiceResponse response = invoiceDocumentService.issue("I1");

        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.ISSUED);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV/2026-27/0001");
        assertThat(response.getFxLockedAt()).isNotNull();
        assertThat(response.getGrandTotalInr()).isEqualByComparingTo("83120.00");
    }

    @Test
    void issuingAPrincipalInvoiceForAFutureDepartureDefersRevenueInTheJournal() {
        Booking futureBooking = Booking.builder().id("B1").departureDate(LocalDate.now().plusDays(30)).build();
        Invoice draft = Invoice.builder()
                .id("I1").documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET)
                .clientId("K1").clientName("Arjun Mehta").agentId("A1").bookingId("B1")
                .serviceCategory(InvoiceServiceCategory.PACKAGE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("50000.00")).taxableValueInr(new BigDecimal("50000.00"))
                .gstTotal(BigDecimal.ZERO).gstTotalInr(BigDecimal.ZERO)
                .tcsAmount(BigDecimal.ZERO).tcsAmountInr(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("50000.00")).grandTotalInr(new BigDecimal("50000.00"))
                .build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().id("L1").invoiceId("I1").description("x").build()));
        when(documentNumberService.next(DocumentKind.PACKAGE_INVOICE, LocalDate.now())).thenReturn("PKG/2026-27/0001");
        when(bookingRepository.findById("B1")).thenReturn(Optional.of(futureBooking));

        invoiceDocumentService.issue("I1");

        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor = ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_DEFERRED);
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("1200");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("50000.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("2120");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("50000.00");
    }

    @Test
    void issuingAPrincipalInvoiceWithNoDepartureDateRecognisesRevenueImmediately() {
        Invoice draft = Invoice.builder()
                .id("I1").documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET)
                .clientId("K1").clientName("Arjun Mehta").agentId("A1")
                .serviceCategory(InvoiceServiceCategory.HOTEL)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("20000.00")).taxableValueInr(new BigDecimal("20000.00"))
                .gstTotal(new BigDecimal("1000.00")).gstTotalInr(new BigDecimal("1000.00"))
                .tcsAmount(BigDecimal.ZERO).tcsAmountInr(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("21000.00")).grandTotalInr(new BigDecimal("21000.00"))
                .build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1"))
                .thenReturn(List.of(InvoiceLineItem.builder().id("L1").invoiceId("I1").description("x").build()));
        when(documentNumberService.next(DocumentKind.HOTEL_INVOICE, LocalDate.now())).thenReturn("HTL/2026-27/0001");

        invoiceDocumentService.issue("I1");

        ArgumentCaptor<com.voyra.crm.models.JournalPosting> captor = ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        com.voyra.crm.models.JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.INVOICE_RAISED_RECOGNIZED);
        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("4030"); // HOTEL is InvoiceServiceCategory index 2 -> (2+1)*10
        BigDecimal debitTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = posting.lines().stream().map(com.voyra.crm.models.JournalLinePosting::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void issuingWithNoLinesIsRejected() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of());

        assertThatThrownBy(() -> invoiceDocumentService.issue("I1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one line");
    }

    @Test
    void anIssuedInvoiceRejectsEveryEdit() {
        Invoice issued = Invoice.builder().id("I1").status(InvoiceLifecycle.ISSUED).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(issued));

        InvoiceDraftRequest request = new InvoiceDraftRequest();
        request.setSupplyNature(SupplyNature.DOMESTIC_PACKAGE);

        assertThatThrownBy(() -> invoiceDocumentService.updateDraft("I1", request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletingADraftNeverTouchesTheNumberSequence() {
        Invoice draft = Invoice.builder().id("I1").status(InvoiceLifecycle.DRAFT).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));

        invoiceDocumentService.deleteDraft("I1");

        org.mockito.Mockito.verifyNoInteractions(documentNumberService);
        org.mockito.Mockito.verify(invoiceRepository).delete(draft);
    }

    @Test
    void cancellingATaxInvoiceWithReceiptsPointsToACreditNote() {
        Invoice issuedWithReceipts = Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).amountReceived(new BigDecimal("500.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(issuedWithReceipts));

        assertThatThrownBy(() -> invoiceDocumentService.cancel("I1", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credit note");
    }

    @Test
    void cancellingAProformaWithAdvanceReceiptsPointsToAReversal() {
        Invoice proformaWithAdvances = Invoice.builder().id("I1").documentType(InvoiceDocumentType.PROFORMA)
                .status(InvoiceLifecycle.PROFORMA_ISSUED).amountReceived(new BigDecimal("500.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(proformaWithAdvances));

        assertThatThrownBy(() -> invoiceDocumentService.cancel("I1", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reverse the receipts");
    }

    @Test
    void cancellingAnIssuedInvoiceWithNoReceiptsSucceeds() {
        Invoice issued = Invoice.builder().id("I1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.ISSUED).amountReceived(BigDecimal.ZERO)
                .balanceDue(new BigDecimal("12000.00")).balanceDueInr(new BigDecimal("12000.00")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(issued));

        InvoiceResponse response = invoiceDocumentService.cancel("I1", "Raised in error");

        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.CANCELLED);
        assertThat(response.getCancelReason()).isEqualTo("Raised in error");
        // BUG-CRX-04: nothing is owed on a cancelled invoice.
        assertThat(response.getBalanceDue()).isEqualByComparingTo("0");
        assertThat(issued.getBalanceDueInr()).isEqualByComparingTo("0");
    }

    @Test
    void issuingAProformaIsRetiredAndAlwaysRejected() {
        assertThatThrownBy(() -> invoiceDocumentService.issueProforma("I1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
    }

    @Test
    void convertingAProformaCreatesANewIssuedTaxInvoiceAndCancelsTheProforma() {
        Invoice proforma = Invoice.builder().id("P1").documentType(InvoiceDocumentType.PROFORMA)
                .status(InvoiceLifecycle.PROFORMA_ISSUED).invoiceNumber("PI/2026-27/0001")
                .clientId("K1").clientName("Arjun Mehta").agentId("A1")
                .supplyNature(SupplyNature.DOMESTIC_PACKAGE).taxTreatment(TaxTreatment.INTRA_STATE)
                .placeOfSupplyCode("27").currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .grandTotal(new BigDecimal("1000.00")).grandTotalInr(new BigDecimal("1000.00")).build();
        when(invoiceRepository.findById("P1")).thenReturn(Optional.of(proforma));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("P1"))
                .thenReturn(List.of(InvoiceLineItem.builder().id("L1").invoiceId("P1").description("x").build()));
        when(invoiceRepository.existsById(any())).thenReturn(false);
        when(invoiceLineItemRepository.existsById(any())).thenReturn(false);
        when(documentNumberService.next(DocumentKind.TAX_INVOICE, LocalDate.now())).thenReturn("INV/2026-27/0001");
        PaymentReceipt advance = PaymentReceipt.builder().id("R1").invoiceId("P1").direction(ReceiptDirection.RECEIPT)
                .amount(new BigDecimal("400.00")).paymentMode(PaymentMode.UPI).isAdvance(true).build();
        when(paymentReceiptRepository.findByInvoiceIdAndIsAdvanceTrue("P1")).thenReturn(List.of(advance));

        InvoiceResponse response = invoiceDocumentService.convertToTaxInvoice("P1");

        assertThat(response.getDocumentType()).isEqualTo(InvoiceDocumentType.TAX_INVOICE);
        assertThat(response.getInvoiceNumber()).isEqualTo("INV/2026-27/0001");
        assertThat(response.getStatus()).isEqualTo(InvoiceLifecycle.PARTIALLY_PAID);
        assertThat(response.getAmountReceived()).isEqualByComparingTo("400.00");
        assertThat(response.getBalanceDue()).isEqualByComparingTo("600.00");
        assertThat(advance.getInvoiceId()).isNotEqualTo("P1");

        org.mockito.ArgumentCaptor<Invoice> savedProforma = org.mockito.ArgumentCaptor.forClass(Invoice.class);
        org.mockito.Mockito.verify(invoiceRepository, org.mockito.Mockito.times(2)).save(savedProforma.capture());
        Invoice cancelledProforma = savedProforma.getAllValues().get(1);
        assertThat(cancelledProforma.getStatus()).isEqualTo(InvoiceLifecycle.CANCELLED);
        assertThat(cancelledProforma.getCancelReason()).isEqualTo("Converted to INV/2026-27/0001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listItemCarriesMoneyReceivedAndCreditsSeparatelyInInr() {
        Invoice invoice = Invoice.builder().id("I1").fxRateToInr(new BigDecimal("2"))
                .grandTotal(new BigDecimal("6180.00")).grandTotalInr(new BigDecimal("12360.00"))
                .amountReceived(new BigDecimal("6180.00")).creditNoteTotal(new BigDecimal("6030.00"))
                .balanceDue(new BigDecimal("-6030.00")).balanceDueInr(new BigDecimal("-12060.00")).build();
        when(invoiceRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(invoice));

        List<com.voyra.crm.dto.InvoiceListItemResponse> items = (List<com.voyra.crm.dto.InvoiceListItemResponse>)
                invoiceDocumentService.list(null, null, null, null, null, null);

        assertThat(items.get(0).getAmountReceivedInr()).isEqualByComparingTo("12360.00");
        assertThat(items.get(0).getCreditNoteTotalInr()).isEqualByComparingTo("12060.00");
    }

    // ---- several bookings on one invoice ----------------------------------------------------

    private Booking flight(String id, long number, String client, String vendor, String price, String cost) {
        return Booking.builder().id(id).bookingNumber(number).clientId(client).clientName("Vishal").agentId("A1").agentName("Liam")
                .vendorId(vendor).supplier("MMT").type(BookingType.FLIGHT).internationalTrip(true).destination("Dubai")
                .bookingStatus(BookingStatus.CONFIRMED).paymentStatus(PaymentStatus.PENDING)
                .sellingPrice(new BigDecimal(price)).netCost(new BigDecimal(cost)).build();
    }

    private com.voyra.crm.entity.BookingDocument voucher(String id, String bookingId, String ref, String cost, String price) {
        return com.voyra.crm.entity.BookingDocument.builder().id(id).bookingId(bookingId).referenceNumber(ref)
                .netCost(new BigDecimal(cost)).sellingPrice(new BigDecimal(price)).build();
    }

    /** Two flights of one client and supplier, each with one priced voucher, and 18% GST as the default. */
    private void twoFlights(Booking b1, Booking b2) {
        for (Booking b : List.of(b1, b2)) {
            org.mockito.Mockito.lenient().when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
            org.mockito.Mockito.lenient().when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        }
        org.mockito.Mockito.lenient().when(bookingRepository.findAllById(any())).thenReturn(List.of(b1, b2));
        org.mockito.Mockito.lenient().when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1"))
                .thenReturn(List.of(voucher("V1", "B1", "NAHKSJ", "105800", "107800")));
        org.mockito.Mockito.lenient().when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B2"))
                .thenReturn(List.of(voucher("V2", "B2", "HYJJUY", "9000", "10000")));
        org.mockito.Mockito.lenient().when(bookingDocumentRepository.findAllById(any())).thenReturn(List.of(
                brokenDown(voucher("V1", "B1", "NAHKSJ", "105800", "107800")), brokenDown(voucher("V2", "B2", "HYJJUY", "9000", "10000"))));
        org.mockito.Mockito.lenient().when(clientService.findAccessibleClient("K1")).thenReturn(client());
        com.voyra.crm.entity.TaxRateConfig gst = com.voyra.crm.entity.TaxRateConfig.builder().id("G18")
                .taxKind(com.voyra.crm.enums.TaxKind.GST).label("GST 18%").ratePercent(new BigDecimal("18.000")).build();
        org.mockito.Mockito.lenient().when(taxRateConfigRepository.findByTaxKindAndSupplyNatureAndIsDefaultTrueAndIsActiveTrue(any(), any()))
                .thenReturn(Optional.of(gst));
        org.mockito.Mockito.lenient().when(taxRateConfigRepository.findById("G18")).thenReturn(Optional.of(gst));
        org.mockito.Mockito.lenient().when(taxEngine.resolveTreatment(eq("K1"), any(), anyBoolean(), any())).thenReturn(TaxTreatment.INTRA_STATE);
    }

    @Test
    void oneDraftBillsEveryVoucherOfTheBookingsWithGstOnTheMarkupOnly() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));

        InvoiceResponse draft = invoiceDocumentService.createDrafts(List.of("B2", "B1"));

        assertThat(draft.getStatus()).isEqualTo(InvoiceLifecycle.DRAFT);
        assertThat(draft.getBookingId()).isEqualTo("B1");
        assertThat(draft.getBillingModel()).isEqualTo(com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT);
        assertThat(draft.getLines()).extracting(com.voyra.crm.dto.InvoiceLineItemResponse::getBookingDocumentId).containsExactly("V1", "V2");
        assertThat(draft.getLines()).extracting(com.voyra.crm.dto.InvoiceLineItemResponse::getBookingId).containsExactly("B1", "B2");
        assertThat(draft.getTaxableValue()).isEqualByComparingTo("117800");
        assertThat(draft.getPassThroughAmount()).isEqualByComparingTo("114800");
        // 18% of the 3,000 markup, not of the fare.
        assertThat(draft.getGstTotal()).isEqualByComparingTo("540.00");
        assertThat(draft.getGrandTotal()).isEqualByComparingTo("118340.00");
    }

    @Test
    void oneDraftTakesOnlyOneClientOneSupplierAndConfirmedBookings() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K2", "V1", "10000", "9000"));
        assertThatThrownBy(() -> invoiceDocumentService.createDrafts(List.of("B1", "B2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same client, supplier");

        Booking pending = flight("B3", 44, "K1", "V1", "5000", "4000");
        pending.setBookingStatus(BookingStatus.PENDING);
        when(bookingRepository.findByIdForUpdate("B3")).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> invoiceDocumentService.createDrafts(List.of("B3")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("isn't confirmed");

        Booking noSupplier = flight("B4", 45, "K1", null, "5000", "4000");
        when(bookingRepository.findByIdForUpdate("B4")).thenReturn(Optional.of(noSupplier));
        // On its own it needs no supplier; combined, it does.
        assertThatThrownBy(() -> invoiceDocumentService.createDrafts(List.of("B1", "B4")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BK00000045 has no supplier");
    }

    @Test
    void aBookingAlreadyOnADraftIsNotDraftedAgain() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));
        org.mockito.Mockito.lenient().when(invoiceRepository.findDraftsForBooking("B2")).thenReturn(List.of(Invoice.builder().id("D1").build()));
        assertThatThrownBy(() -> invoiceDocumentService.createDrafts(List.of("B1", "B2")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BK00000043 is already on a draft invoice");
    }

    @Test
    void generatingADraftOfTwoBookingsSyncsBothAndRecognisesOnIssue() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").clientId("K1").documentType(InvoiceDocumentType.TAX_INVOICE)
                .status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET).serviceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL)
                .billingModel(com.voyra.crm.enums.InvoiceBillingModel.COMMISSION_AGENT).passThroughAmount(new BigDecimal("114800"))
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE).taxableValue(new BigDecimal("117800")).taxableValueInr(new BigDecimal("117800"))
                .grandTotal(new BigDecimal("117800")).grandTotalInr(new BigDecimal("117800")).balanceDue(new BigDecimal("117800")).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().invoiceId("I1").bookingId("B1").bookingDocumentId("V1").lineSubtotal(new BigDecimal("107800")).build(),
                InvoiceLineItem.builder().invoiceId("I1").bookingId("B2").bookingDocumentId("V2").lineSubtotal(new BigDecimal("10000")).build()));
        when(invoiceLineItemRepository.findBookingIdsByInvoiceId("I1")).thenReturn(List.of("B1", "B2"));
        when(documentNumberService.next(any(), any())).thenReturn("ITI/26-27/0001");

        invoiceDocumentService.issue("I1");

        org.mockito.Mockito.verify(bookingAccountingSync).syncPayment("B1");
        org.mockito.Mockito.verify(bookingAccountingSync).syncPayment("B2");
        ArgumentCaptor<com.voyra.crm.models.JournalPosting> posted = ArgumentCaptor.forClass(com.voyra.crm.models.JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(posted.capture());
        assertThat(posted.getValue().narration()).contains("BK00000042, BK00000043").contains("NAHKSJ, HYJJUY");
    }

    private static com.voyra.crm.entity.BookingDocument brokenDown(com.voyra.crm.entity.BookingDocument d) {
        d.setCharges(new java.util.LinkedHashMap<>(java.util.Map.of("basic", d.getNetCost())));
        return d;
    }

    /** A flight ticket's invoice prints its fare breakdown, so it waits for the accountant to enter one. */
    @Test
    void aFlightVoucherWithNoFareBreakdownIsNotInvoiced() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET)
                .serviceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().invoiceId("I1").bookingId("B1").bookingDocumentId("V1").lineSubtotal(new BigDecimal("107800")).build()));
        when(invoiceLineItemRepository.findBookingIdsByInvoiceId("I1")).thenReturn(List.of("B1"));
        when(bookingDocumentRepository.findAllById(any())).thenReturn(List.of(voucher("V1", "B1", "NAHKSJ", "105800", "107800")));

        assertThatThrownBy(() -> invoiceDocumentService.issue("I1"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Add the fare breakdown for PNR NAHKSJ before generating the invoice");
    }

    @Test
    void aDraftLineCarriesItsTicketsTravellersFlightsAndPurchaseCurrency() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));
        com.voyra.crm.entity.BookingDocument ticket = voucher("V1", "B1", "NAHKSJ", "105800", "107800");
        ticket.setDocType(com.voyra.crm.enums.BookingDocumentType.E_TICKET);
        ticket.setTravellers(List.of(new com.voyra.crm.dto.VoucherTraveller("M1", "Mukesh", com.voyra.crm.enums.PaxType.ADULT)));
        ticket.setLegs(List.of(com.voyra.crm.dto.VoucherLeg.builder().from("BOM").to("DXB").travelDate(java.time.LocalDate.of(2026, 12, 19)).build()));
        ticket.setPurchaseCurrency("AED");
        ticket.setPurchaseRate(new BigDecimal("22.85"));
        when(bookingDocumentRepository.findByBookingIdOrderBySortOrderAsc("B1")).thenReturn(List.of(ticket));

        InvoiceResponse draft = invoiceDocumentService.createDrafts(List.of("B1"));

        com.voyra.crm.dto.InvoiceLineItemResponse line = draft.getLines().get(0);
        assertThat(line.getTravellers()).extracting(com.voyra.crm.dto.VoucherTraveller::getName).containsExactly("Mukesh");
        assertThat(line.getLegs()).extracting(com.voyra.crm.dto.VoucherLeg::getTo).containsExactly("DXB");
        assertThat(line.getDescription()).contains("Mukesh");
    }

    @Test
    void anAirTicketWithNoTravellersIsNotInvoiced() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET)
                .serviceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().invoiceId("I1").bookingId("B1").bookingDocumentId("V1").lineSubtotal(new BigDecimal("107800")).build()));
        when(invoiceLineItemRepository.findBookingIdsByInvoiceId("I1")).thenReturn(List.of("B1"));
        com.voyra.crm.entity.BookingDocument ticket = brokenDown(voucher("V1", "B1", "NAHKSJ", "105800", "107800"));
        ticket.setDocType(com.voyra.crm.enums.BookingDocumentType.E_TICKET);
        when(bookingDocumentRepository.findAllById(any())).thenReturn(List.of(ticket));

        assertThatThrownBy(() -> invoiceDocumentService.issue("I1")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Add the travellers for PNR NAHKSJ on the booking's voucher - delete this draft first, then draft it again");
    }

    @Test
    void aDraftWhoseVouchersChangedIsNotIssued() {
        twoFlights(flight("B1", 42, "K1", "V1", "107800", "105800"), flight("B2", 43, "K1", "V1", "10000", "9000"));
        Invoice draft = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET)
                .serviceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(draft));
        // Saved when B1's voucher was priced lower.
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().invoiceId("I1").bookingId("B1").bookingDocumentId("V1").lineSubtotal(new BigDecimal("100000")).build()));

        assertThatThrownBy(() -> invoiceDocumentService.issue("I1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("save the draft again");
    }

    @Test
    void anInvoiceIsNotDatedAheadOrBeforeItsSeries() {
        Invoice future = Invoice.builder().id("I1").bookingId("B1").status(InvoiceLifecycle.DRAFT).supplyNature(SupplyNature.AIR_TICKET)
                .serviceCategory(InvoiceServiceCategory.HOTEL).invoiceDate(LocalDate.now().plusDays(1)).build();
        when(invoiceRepository.findById("I1")).thenReturn(Optional.of(future));
        when(invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc("I1")).thenReturn(List.of(
                InvoiceLineItem.builder().invoiceId("I1").bookingId("B1").lineSubtotal(new BigDecimal("100")).build()));
        assertThatThrownBy(() -> invoiceDocumentService.issue("I1")).hasMessageContaining("can't be in the future");

        future.setInvoiceDate(LocalDate.now().minusDays(5));
        when(invoiceRepository.latestIssuedDate(eq(InvoiceServiceCategory.HOTEL), any())).thenReturn(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> invoiceDocumentService.issue("I1")).hasMessageContaining("already issued up to");
    }
}
