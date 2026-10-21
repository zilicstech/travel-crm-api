package com.voyra.crm.service;

import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.entity.SupplierInvoiceLineItem;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.ItcEligibility;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.JournalLinePosting;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.repository.BookingCostComponentRepository;
import com.voyra.crm.repository.BookingRepository;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierInvoiceServiceTest {

    @Mock
    private com.voyra.crm.repository.InvoiceRepository invoiceRepository;

    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private SupplierInvoiceLineItemRepository lineItemRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingCostComponentRepository bookingCostComponentRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private SupplierLedgerService supplierLedgerService;
    @Mock
    private AuditService auditService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private JournalService journalService;
    @Mock
    private JournalEntryRepository journalEntryRepository;

    @org.mockito.Spy

    private com.voyra.crm.service.BaseCurrency baseCurrency = new com.voyra.crm.service.BaseCurrency(null);


    @InjectMocks
    private SupplierInvoiceService supplierInvoiceService;

    @BeforeEach
    void authenticateAsOwner() {
        CustomUserPrincipal principal = new CustomUserPrincipal("O1", "owner", UserType.AGENCY_OWNER, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void stubAgency() {
        when(tenantRepository.findById("T1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Tenant.builder().id("T1").stateCode("27").build()));
    }

    @Test
    void approvingABillWithNoBookingPostsToMiscellaneousPurchasesAndInputGst() {
        SupplierInvoice invoice = SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("Ethiopian Airlines")
                .status(SupplierInvoiceStatus.DRAFT)
                .itcEligibility(ItcEligibility.ELIGIBLE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("5000.00")).taxableValueInr(new BigDecimal("5000.00"))
                .gstTotal(new BigDecimal("250.00")).gstTotalInr(new BigDecimal("250.00"))
                .grandTotal(new BigDecimal("5250.00")).grandTotalInr(new BigDecimal("5250.00"))
                .roundOff(BigDecimal.ZERO)
                .amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .build();
        SupplierInvoiceLineItem line = SupplierInvoiceLineItem.builder()
                .id("L1").supplierInvoiceId("SI1").sortOrder(0).description("Air ticket")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("5000.00"))
                .taxableValue(new BigDecimal("5000.00")).lineTotal(new BigDecimal("5250.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO)
                .build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(line));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Ethiopian Airlines").build()));
        stubAgency();

        supplierInvoiceService.approve("SI1", null);

        assertThat(invoice.getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.SUPPLIER_BILL_BOOKED);
        assertThat(posting.lines()).hasSize(3);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("5080"); // no booking -> MISCELLANEOUS -> index 7 -> (7+1)*10
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("5000.00");
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("1400");
        assertThat(posting.lines().get(1).debitAmount()).isEqualByComparingTo("250.00");
        assertThat(posting.lines().get(2).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(2).creditAmount()).isEqualByComparingTo("5250.00");
    }

    /** An air ticket is billed as commission agent: its cost passes through to the client, so the bill clears 2210, not a purchase. */
    @Test
    void approvingAFlightBookingsBillClearsThePassThroughNotPurchases() {
        SupplierInvoice invoice = SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("IndiGo").bookingId("B1")
                .status(SupplierInvoiceStatus.DRAFT).itcEligibility(ItcEligibility.ELIGIBLE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("10000.00")).taxableValueInr(new BigDecimal("10000.00"))
                .gstTotal(BigDecimal.ZERO).gstTotalInr(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("10000.00")).grandTotalInr(new BigDecimal("10000.00"))
                .roundOff(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .build();
        SupplierInvoiceLineItem line = SupplierInvoiceLineItem.builder()
                .id("L1").supplierInvoiceId("SI1").sortOrder(0).description("DEL-BOM")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("10000.00"))
                .taxableValue(new BigDecimal("10000.00")).lineTotal(new BigDecimal("10000.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO)
                .build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(line));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("IndiGo").build()));
        org.mockito.Mockito.lenient().when(bookingRepository.findById("B1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Booking.builder().id("B1").type(com.voyra.crm.enums.BookingType.FLIGHT).internationalTrip(false).build()));
        stubAgency();

        supplierInvoiceService.approve("SI1", null);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        assertThat(captor.getValue().lines().get(0).accountCode()).isEqualTo("2210");
        assertThat(captor.getValue().lines().get(0).debitAmount()).isEqualByComparingTo("10000.00");
        assertThat(captor.getValue().lines().get(1).accountCode()).isEqualTo("2200");
    }

    // ---- commission the supplier pays the agency ------------------------------------------

    private SupplierInvoice flightBillWithCommission(SupplierInvoiceStatus status) {
        return SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("IndiGo").bookingId("B1")
                .status(status).itcEligibility(ItcEligibility.ELIGIBLE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("10000.00")).taxableValueInr(new BigDecimal("10000.00"))
                .gstTotal(BigDecimal.ZERO).gstTotalInr(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("10000.00")).grandTotalInr(new BigDecimal("10000.00"))
                .commission(new BigDecimal("410.00")).supplierTds(new BigDecimal("8.20"))
                .roundOff(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .balanceDue(new BigDecimal("9598.20")).balanceDueInr(new BigDecimal("9598.20"))
                .build();
    }

    /** The supplier keeps its commission back: the agency owes the bill less it, plus the TDS the supplier deducted. */
    @Test
    void aVoucherWithCommissionDraftsABillOwingTheNet() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("IndiGo").build()));
        com.voyra.crm.entity.Booking booking = com.voyra.crm.entity.Booking.builder().id("B1")
                .type(com.voyra.crm.enums.BookingType.FLIGHT).destination("BOM").build();

        supplierInvoiceService.draftForVoucher(booking, "V1", null, null, new BigDecimal("10000"), null,
                new BigDecimal("410.00"), new BigDecimal("8.20"));

        ArgumentCaptor<SupplierInvoice> saved = ArgumentCaptor.forClass(SupplierInvoice.class);
        org.mockito.Mockito.verify(supplierInvoiceRepository).save(saved.capture());
        assertThat(saved.getValue().getGrandTotal()).isEqualByComparingTo("10000");
        assertThat(saved.getValue().payable()).isEqualByComparingTo("9598.20");
        assertThat(saved.getValue().getBalanceDue()).isEqualByComparingTo("9598.20");
        assertThat(saved.getValue().getBalanceDueInr()).isEqualByComparingTo("9598.20");
    }

    /** Booked: the gross clears the pass-through, the commission is income and the supplier's TDS is claimed back. */
    @Test
    void approvingABillWithCommissionBooksItAsIncomeAndTheTdsAsReceivable() {
        SupplierInvoice invoice = flightBillWithCommission(SupplierInvoiceStatus.DRAFT);
        SupplierInvoiceLineItem line = SupplierInvoiceLineItem.builder()
                .id("L1").supplierInvoiceId("SI1").sortOrder(0).description("DEL-BOM")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("10000.00"))
                .taxableValue(new BigDecimal("10000.00")).lineTotal(new BigDecimal("10000.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO)
                .build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(line));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("IndiGo").build()));
        org.mockito.Mockito.lenient().when(bookingRepository.findById("B1")).thenReturn(Optional.of(
                com.voyra.crm.entity.Booking.builder().id("B1").type(com.voyra.crm.enums.BookingType.FLIGHT).internationalTrip(false).build()));
        stubAgency();

        supplierInvoiceService.approve("SI1", null);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        var lines = captor.getValue().lines();
        assertThat(lines).extracting(l -> l.accountCode()).containsExactly("2210", "1410", "2200", "4610");
        assertThat(lines.get(1).debitAmount()).isEqualByComparingTo("8.20");
        assertThat(lines.get(2).creditAmount()).isEqualByComparingTo("9598.20");
        assertThat(lines.get(3).creditAmount()).isEqualByComparingTo("410.00");
        BigDecimal debits = lines.stream().map(l -> l.debitAmount() != null ? l.debitAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream().map(l -> l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
        // The supplier's ledger is credited what is owed, the net.
        ArgumentCaptor<com.voyra.crm.models.SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(com.voyra.crm.models.SupplierLedgerPosting.class);
        org.mockito.Mockito.verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().creditAmountInr()).isEqualByComparingTo("9598.20");
    }

    /** A cancelled ticket: the supplier takes the commission back - the income and the TDS claim are reversed. */
    @Test
    void recallingTheCommissionOnABookedBillReversesTheIncomeAndRaisesWhatIsOwed() {
        SupplierInvoice bill = flightBillWithCommission(SupplierInvoiceStatus.APPROVED);
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill));

        supplierInvoiceService.recallCommission("SI1");

        assertThat(bill.getCommission()).isEqualByComparingTo("0");
        assertThat(bill.getBalanceDue()).isEqualByComparingTo("10000");
        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        assertThat(captor.getValue().purpose()).isEqualTo(com.voyra.crm.enums.JournalPurpose.SUPPLIER_COMMISSION_RECALLED);
        assertThat(captor.getValue().lines()).extracting(l -> l.accountCode()).containsExactly("4610", "1410", "2200");
        assertThat(captor.getValue().lines().get(2).creditAmount()).isEqualByComparingTo("401.80");
        // Its own supplier-ledger entry - the bill's BILL_BOOKED row already holds that (source, type) key.
        ArgumentCaptor<com.voyra.crm.models.SupplierLedgerPosting> ledger = ArgumentCaptor.forClass(com.voyra.crm.models.SupplierLedgerPosting.class);
        org.mockito.Mockito.verify(supplierLedgerService).post(ledger.capture());
        assertThat(ledger.getValue().entryType()).isEqualTo(com.voyra.crm.enums.SupplierLedgerEntryType.COMMISSION_RECALLED);
    }

    @Test
    void recallingTheCommissionOnAnUnpostedBillOnlyDropsIt() {
        SupplierInvoice bill = flightBillWithCommission(SupplierInvoiceStatus.DRAFT);
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill));

        supplierInvoiceService.recallCommission("SI1");

        assertThat(bill.payable()).isEqualByComparingTo("10000");
        org.mockito.Mockito.verifyNoInteractions(journalService);
    }

    /** The accountant sets the commission on the voucher: an unapproved bill owes the net, an approved one is refused. */
    @Test
    void theCommissionOnAnUnapprovedBillCanChangeAndOnAnApprovedOneCannot() {
        SupplierInvoice bill = flightBillWithCommission(SupplierInvoiceStatus.DRAFT);
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(bill));

        supplierInvoiceService.setDraftCommission("SI1", new BigDecimal("300"), new BigDecimal("6"));

        assertThat(bill.getBalanceDue()).isEqualByComparingTo("9706");

        bill.setStatus(SupplierInvoiceStatus.APPROVED);
        assertThatThrownBy(() -> supplierInvoiceService.setDraftCommission("SI1", BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessageContaining("already approved");
    }

    /** A voucher bought in AED drafts its bill in AED at the rate entered, so its base total is the voucher's cost. */
    @Test
    void aVoucherBoughtInAedDraftsAnAedBillAtItsRate() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Dubai DMC").build()));
        when(supplierInvoiceRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        com.voyra.crm.entity.Booking booking = com.voyra.crm.entity.Booking.builder().id("B1")
                .type(com.voyra.crm.enums.BookingType.HOTEL).destination("Dubai").build();

        supplierInvoiceService.draftForVoucher(booking, "V1", "HV-1", "AED", new BigDecimal("1000"), new BigDecimal("22.85"), null, null);

        ArgumentCaptor<SupplierInvoice> saved = ArgumentCaptor.forClass(SupplierInvoice.class);
        org.mockito.Mockito.verify(supplierInvoiceRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrencyCode()).isEqualTo("AED");
        assertThat(saved.getValue().getFxRateToInr()).isEqualByComparingTo("22.85");
        assertThat(saved.getValue().getFxRateSource()).isEqualTo("MANUAL");
        assertThat(saved.getValue().getGrandTotal()).isEqualByComparingTo("1000");
        assertThat(saved.getValue().getGrandTotalInr()).isEqualByComparingTo("22850.00");
    }

    @Test
    void aVoucherBoughtInBaseDraftsABaseBill() {
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("IndiGo").build()));
        com.voyra.crm.entity.Booking booking = com.voyra.crm.entity.Booking.builder().id("B1")
                .type(com.voyra.crm.enums.BookingType.FLIGHT).destination("BOM").build();

        supplierInvoiceService.draftForVoucher(booking, "V1", null, null, new BigDecimal("4000"), null, null, null);

        ArgumentCaptor<SupplierInvoice> saved = ArgumentCaptor.forClass(SupplierInvoice.class);
        org.mockito.Mockito.verify(supplierInvoiceRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrencyCode()).isEqualTo("INR");
        assertThat(saved.getValue().getFxRateToInr()).isEqualByComparingTo("1");
        assertThat(saved.getValue().getGrandTotalInr()).isEqualByComparingTo("4000.00");
    }

    /** A bill over the booking's cost cap that an accountant sent up is booked when the owner approves it with a reason. */
    @Test
    void theOwnerApprovesABillWaitingForOverride() {
        SupplierInvoice invoice = SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("Skyline").bookingId("B1").category(com.voyra.crm.enums.BookingType.HOTEL)
                .status(SupplierInvoiceStatus.PENDING_APPROVAL).itcEligibility(ItcEligibility.INELIGIBLE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("5600.00")).taxableValueInr(new BigDecimal("5600.00"))
                .gstTotal(BigDecimal.ZERO).gstTotalInr(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("5600.00")).grandTotalInr(new BigDecimal("5600.00"))
                .roundOff(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .build();
        SupplierInvoiceLineItem line = SupplierInvoiceLineItem.builder()
                .id("L1").supplierInvoiceId("SI1").sortOrder(0).description("Hotel stay")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("5600.00"))
                .taxableValue(new BigDecimal("5600.00")).lineTotal(new BigDecimal("5600.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO)
                .build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(line));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Skyline").build()));
        when(bookingCostComponentRepository.findByBookingIdAndVendorId("B1", "V1")).thenReturn(List.of(
                com.voyra.crm.entity.BookingCostComponent.builder().bookingId("B1").vendorId("V1").netCost(new BigDecimal("5000.00")).build()));
        org.mockito.Mockito.lenient().when(bookingRepository.findById("B1")).thenReturn(Optional.empty());
        stubAgency();

        supplierInvoiceService.approve("SI1", "Fare increased before ticketing");

        assertThat(invoice.getStatus()).isEqualTo(SupplierInvoiceStatus.APPROVED);
        org.mockito.Mockito.verify(journalService).post(any(JournalPosting.class));
    }

    /** BUG-CRX-15: a hand-made bill (no booking) posts to its own category's purchase account, not Miscellaneous. */
    @Test
    void approvingAHandMadeHotelBillPostsToHotelPurchases() {
        SupplierInvoice invoice = SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("Agoda").category(com.voyra.crm.enums.BookingType.HOTEL)
                .status(SupplierInvoiceStatus.DRAFT).itcEligibility(ItcEligibility.INELIGIBLE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("500.00")).taxableValueInr(new BigDecimal("500.00"))
                .gstTotal(BigDecimal.ZERO).gstTotalInr(BigDecimal.ZERO)
                .grandTotal(new BigDecimal("500.00")).grandTotalInr(new BigDecimal("500.00"))
                .roundOff(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .build();
        SupplierInvoiceLineItem line = SupplierInvoiceLineItem.builder()
                .id("L1").supplierInvoiceId("SI1").sortOrder(0).description("Hotel stay")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("500.00"))
                .taxableValue(new BigDecimal("500.00")).lineTotal(new BigDecimal("500.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO)
                .build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(line));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Agoda").build()));
        stubAgency();

        supplierInvoiceService.approve("SI1", null);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        assertThat(captor.getValue().lines().get(0).accountCode()).isEqualTo("5030");
        assertThat(captor.getValue().lines().get(1).accountCode()).isEqualTo("2200");
    }

    @Test
    void approvingAnIneligibleItcBillFoldsGstIntoThePurchaseDebit() {
        SupplierInvoice invoice = SupplierInvoice.builder()
                .id("SI1").vendorId("V1").vendorName("Novotel Goa")
                .status(SupplierInvoiceStatus.DRAFT)
                .itcEligibility(ItcEligibility.INELIGIBLE)
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .taxableValue(new BigDecimal("2000.00")).taxableValueInr(new BigDecimal("2000.00"))
                .gstTotal(new BigDecimal("100.00")).gstTotalInr(new BigDecimal("100.00"))
                .grandTotal(new BigDecimal("2100.00")).grandTotalInr(new BigDecimal("2100.00"))
                .roundOff(BigDecimal.ZERO)
                .amountPaid(BigDecimal.ZERO).creditNoteTotal(BigDecimal.ZERO)
                .build();
        SupplierInvoiceLineItem line = SupplierInvoiceLineItem.builder()
                .id("L1").supplierInvoiceId("SI1").sortOrder(0).description("Hotel stay")
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal("2000.00"))
                .taxableValue(new BigDecimal("2000.00")).lineTotal(new BigDecimal("2100.00"))
                .cgstAmount(BigDecimal.ZERO).sgstAmount(BigDecimal.ZERO).igstAmount(BigDecimal.ZERO)
                .build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(lineItemRepository.findBySupplierInvoiceIdOrderBySortOrder("SI1")).thenReturn(List.of(line));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Novotel Goa").build()));
        stubAgency();

        supplierInvoiceService.approve("SI1", null);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        org.mockito.Mockito.verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.lines()).hasSize(2); // no separate 1400 line
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("2100.00"); // 2000 + 100 folded in
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("2100.00");
    }

    @Test
    void anAgentCancellingABookingCanStillCancelItsDraftBill() {
        CustomUserPrincipal agent = new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent, null, List.of()));
        SupplierInvoice draft = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.DRAFT)
                .amountPaid(BigDecimal.ZERO).balanceDue(new BigDecimal("500.00")).balanceDueInr(new BigDecimal("500.00"))
                .dueDate(java.time.LocalDate.now().minusDays(3)).build();
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(draft));

        supplierInvoiceService.cancelForBooking("S1", "Booking cancelled");

        assertThat(draft.getStatus()).isEqualTo(SupplierInvoiceStatus.CANCELLED);
        // BUG-CRX-18: a cancelled bill owes nothing and is never overdue.
        assertThat(draft.getBalanceDueInr()).isEqualByComparingTo("0");
        assertThat(SupplierInvoiceService.toListItem(draft).getOverdue()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierInvoiceService.cancel("S1", "x"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /** An agent cancelling with a supplier charge books the draft bill for it; the payables approve stays owner/accountant only. */
    @Test
    void anAgentCancellingWithAChargeCanBookTheDraftBillButNotApproveFromPayables() {
        CustomUserPrincipal agent = new CustomUserPrincipal("A1", "agent", UserType.AGENT, "T1");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent, null, List.of()));
        SupplierInvoice draft = SupplierInvoice.builder().id("S1").status(SupplierInvoiceStatus.DRAFT)
                .amountPaid(BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findById("S1")).thenReturn(Optional.of(draft));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierInvoiceService.approve("S1", null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // The booking path gets past the role gate and on to the bill's own checks (this draft has no lines).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierInvoiceService.approveForBooking("S1", "charge"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("At least one line");
    }

    /** BUG-MO-02: each bill row names its booking (BK number and client), so drafted bills can be told apart. */
    @Test
    void billRowsNameTheirBooking() {
        com.voyra.crm.entity.SupplierInvoice bill = com.voyra.crm.entity.SupplierInvoice.builder().id("S1").bookingId("B1")
                .status(com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT).balanceDue(java.math.BigDecimal.ZERO).build();
        when(supplierInvoiceRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(java.util.List.of(bill));
        when(bookingRepository.findAllById(any())).thenReturn(java.util.List.of(
                com.voyra.crm.entity.Booking.builder().id("B1").bookingNumber(995L).clientName("CRM Money Client").build()));

        var row = supplierInvoiceService.list(null, null, null).get(0);

        assertThat(row.getBookingRef()).isEqualTo("BK00000995");
        assertThat(row.getClientName()).isEqualTo("CRM Money Client");
    }

    /** Round 2 decision: the due date typed on New Bill / Confirm Bill is kept; one before the bill date is refused. */
    @Test
    void aDraftKeepsTheTypedDueDateButNeverOneBeforeTheBillDate() {
        SupplierInvoice invoice = SupplierInvoice.builder().id("SI1").status(SupplierInvoiceStatus.DRAFT).build();
        when(supplierInvoiceRepository.findById("SI1")).thenReturn(Optional.of(invoice));
        when(vendorRepository.findById("V1")).thenReturn(Optional.of(Vendor.builder().id("V1").name("Agoda").paymentTermsDays(15).build()));
        com.voyra.crm.dto.SupplierInvoiceDraftRequest request = new com.voyra.crm.dto.SupplierInvoiceDraftRequest();
        request.setVendorId("V1");
        request.setInvoiceDate(java.time.LocalDate.of(2026, 10, 19));
        request.setDueDate(java.time.LocalDate.of(2026, 11, 30));
        request.setLines(List.of());

        supplierInvoiceService.updateDraft("SI1", request);
        assertThat(invoice.getDueDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 30));

        request.setDueDate(java.time.LocalDate.of(2026, 10, 18));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> supplierInvoiceService.updateDraft("SI1", request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("due date");
    }
}
