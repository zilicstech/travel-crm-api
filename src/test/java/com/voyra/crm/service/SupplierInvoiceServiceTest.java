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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierInvoiceServiceTest {

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
}
