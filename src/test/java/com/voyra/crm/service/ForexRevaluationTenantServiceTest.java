package com.voyra.crm.service;

import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.FxRateSource;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import com.voyra.crm.models.JournalPosting;
import com.voyra.crm.models.ResolvedExchangeRate;
import com.voyra.crm.repository.JournalEntryRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ACCOUNTING_EXPANSION_ARCHITECTURE.md §3.5 - month-end unrealized revaluation. Each test uses a
 * single open foreign bill so the posted journal and the reversal lookup are unambiguous.
 */
@ExtendWith(MockitoExtension.class)
class ForexRevaluationTenantServiceTest {

    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private JournalService journalService;
    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    @InjectMocks
    private ForexRevaluationTenantService revaluationService;

    private static final LocalDate MONTH_END = LocalDate.of(2026, 9, 30);
    private static final LocalDate PRIOR_MONTH_END = LocalDate.of(2026, 8, 31);

    private SupplierInvoice openForeignBill(BigDecimal balanceDue, BigDecimal carryingRate) {
        return SupplierInvoice.builder().id("SI1").vendorId("V1").vendorName("Novotel Goa")
                .supplierInvoiceNumber("INV-001").status(SupplierInvoiceStatus.APPROVED)
                .currencyCode("USD").fxRateToInr(carryingRate)
                .balanceDue(balanceDue).build();
    }

    private void stubNoPriorRevaluation() {
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.SUPPLIER_INVOICE, "SI1@202608", JournalPurpose.FOREX_REVALUATION))
                .thenReturn(Optional.empty());
    }

    @Test
    void aRateRiseOnAnOpenForeignBillPostsAnUnrealizedLoss() {
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of(openForeignBill(new BigDecimal("100.00"), new BigDecimal("83.000000"))));
        stubNoPriorRevaluation();
        when(exchangeRateProvider.resolve("USD", "INR", MONTH_END))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("85.000000"), FxRateSource.DAILY_TABLE, MONTH_END));
        when(journalService.post(any())).thenReturn(JournalEntry.builder().id("JE1").build());

        ForexRevaluationTenantService.RevaluationResult result = revaluationService.revalueOpenForeignBills(MONTH_END);

        assertThat(result.revalued()).isEqualTo(1);
        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.purpose()).isEqualTo(JournalPurpose.FOREX_REVALUATION);
        assertThat(posting.sourceId()).isEqualTo("SI1@202609");
        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.lines().get(0).accountCode()).isEqualTo("5710"); // UNREALIZED_FOREX_LOSS
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("200.00"); // 100 * (85-83)
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(1).partyId()).isEqualTo("V1");
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void aRateFallOnAnOpenForeignBillPostsAnUnrealizedGain() {
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of(openForeignBill(new BigDecimal("100.00"), new BigDecimal("83.000000"))));
        stubNoPriorRevaluation();
        when(exchangeRateProvider.resolve("USD", "INR", MONTH_END))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("81.000000"), FxRateSource.DAILY_TABLE, MONTH_END));
        when(journalService.post(any())).thenReturn(JournalEntry.builder().id("JE2").build());

        revaluationService.revalueOpenForeignBills(MONTH_END);

        ArgumentCaptor<JournalPosting> captor = ArgumentCaptor.forClass(JournalPosting.class);
        verify(journalService).post(captor.capture());
        JournalPosting posting = captor.getValue();

        assertThat(posting.lines().get(0).accountCode()).isEqualTo("2200");
        assertThat(posting.lines().get(0).debitAmount()).isEqualByComparingTo("200.00"); // 100 * |81-83|
        assertThat(posting.lines().get(1).accountCode()).isEqualTo("4710"); // UNREALIZED_FOREX_GAIN
        assertThat(posting.lines().get(1).creditAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void aFullyPaidBillIsNeverConsidered() {
        // The repository query itself excludes balanceDue <= 0 - this test documents that
        // contract by asserting a zero-result query posts nothing, rather than re-testing JPQL.
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of());

        ForexRevaluationTenantService.RevaluationResult result = revaluationService.revalueOpenForeignBills(MONTH_END);

        assertThat(result.revalued()).isZero();
        verify(journalService, never()).post(any());
    }

    @Test
    void anUnchangedRateSkipsPostingEntirely() {
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of(openForeignBill(new BigDecimal("100.00"), new BigDecimal("83.000000"))));
        stubNoPriorRevaluation();
        when(exchangeRateProvider.resolve("USD", "INR", MONTH_END))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("83.000000"), FxRateSource.DAILY_TABLE, MONTH_END));

        ForexRevaluationTenantService.RevaluationResult result = revaluationService.revalueOpenForeignBills(MONTH_END);

        assertThat(result.revalued()).isZero();
        verify(journalService, never()).post(any());
    }

    @Test
    void anOpenPriorPeriodRevaluationIsReversedBeforeThisPeriodsFreshOnePosts() {
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of(openForeignBill(new BigDecimal("100.00"), new BigDecimal("83.000000"))));
        JournalEntry priorEntry = JournalEntry.builder().id("JE_PRIOR").status(JournalStatus.POSTED).build();
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.SUPPLIER_INVOICE, "SI1@202608", JournalPurpose.FOREX_REVALUATION))
                .thenReturn(Optional.of(priorEntry));
        when(exchangeRateProvider.resolve("USD", "INR", MONTH_END))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("85.000000"), FxRateSource.DAILY_TABLE, MONTH_END));
        when(journalService.post(any())).thenReturn(JournalEntry.builder().id("JE_NEW").build());

        revaluationService.revalueOpenForeignBills(MONTH_END);

        verify(journalService).reverse(eq("JE_PRIOR"), any());
        verify(journalService).post(any());
    }

    @Test
    void anAlreadyReversedPriorPeriodEntryIsNotReversedAgain() {
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of(openForeignBill(new BigDecimal("100.00"), new BigDecimal("83.000000"))));
        JournalEntry priorEntry = JournalEntry.builder().id("JE_PRIOR").status(JournalStatus.REVERSED).build();
        when(journalEntryRepository.findBySourceTypeAndSourceIdAndPurpose(
                JournalSourceType.SUPPLIER_INVOICE, "SI1@202608", JournalPurpose.FOREX_REVALUATION))
                .thenReturn(Optional.of(priorEntry));
        when(exchangeRateProvider.resolve("USD", "INR", MONTH_END))
                .thenReturn(new ResolvedExchangeRate(new BigDecimal("85.000000"), FxRateSource.DAILY_TABLE, MONTH_END));
        when(journalService.post(any())).thenReturn(JournalEntry.builder().id("JE_NEW").build());

        revaluationService.revalueOpenForeignBills(MONTH_END);

        verify(journalService, never()).reverse(any(), any());
    }

    @Test
    void aMissingExchangeRateSkipsThatBillRatherThanFailingTheWholeRun() {
        when(supplierInvoiceRepository.findByCurrencyCodeNotAndBalanceDueGreaterThanAndStatusIn(
                eq("INR"), eq(BigDecimal.ZERO), any())).thenReturn(List.of(openForeignBill(new BigDecimal("100.00"), new BigDecimal("83.000000"))));
        stubNoPriorRevaluation();
        when(exchangeRateProvider.resolve("USD", "INR", MONTH_END))
                .thenThrow(new IllegalStateException("no rate"));

        ForexRevaluationTenantService.RevaluationResult result = revaluationService.revalueOpenForeignBills(MONTH_END);

        assertThat(result.revalued()).isZero();
        verify(journalService, never()).post(any());
    }
}
