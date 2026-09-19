package com.voyra.crm.service;

import com.voyra.crm.dto.ArAgeingRowResponse;
import com.voyra.crm.dto.ClientLedgerSummaryResponse;
import com.voyra.crm.dto.LedgerStatementResponse;
import com.voyra.crm.dto.OpeningBalanceRequest;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.CustomerLedgerEntry;
import com.voyra.crm.entity.Invoice;
import com.voyra.crm.enums.InvoiceDocumentType;
import com.voyra.crm.enums.InvoiceLifecycle;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerSourceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.models.LedgerPosting;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.InvoiceRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerLedgerServiceTest {

    @Mock
    private CustomerLedgerEntryRepository ledgerRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private ClientService clientService;

    @InjectMocks
    private CustomerLedgerService customerLedgerService;

    @BeforeEach
    void authenticateAsAccountant() {
        CustomUserPrincipal principal = new CustomUserPrincipal("AC1", "neha", UserType.ACCOUNTANT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Client client() {
        return Client.builder().id("K1").name("Arjun Mehta").build();
    }

    private CustomerLedgerEntry entry(LocalDate date, LedgerEntryType type, BigDecimal debit, BigDecimal credit) {
        return CustomerLedgerEntry.builder()
                .id("L-" + date + "-" + type).clientId("K1").entryDate(date).entryType(type)
                .sourceType(LedgerSourceType.INVOICE).sourceId("S1").narration("x")
                .currencyCode("INR").fxRateToInr(BigDecimal.ONE)
                .debitAmount(debit).creditAmount(credit).debitAmountInr(debit).creditAmountInr(credit)
                .build();
    }

    @Test
    void postSavesAnEntryWithTheGivenFields() {
        when(ledgerRepository.existsById(any())).thenReturn(false);

        customerLedgerService.post(new LedgerPosting(
                "K1", LocalDate.of(2026, 9, 19), LedgerEntryType.INVOICE_RAISED, LedgerSourceType.INVOICE,
                "I1", "INV/2026-27/0001", "Tax invoice raised", "B1", "INR", BigDecimal.ONE,
                new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("1000.00"), BigDecimal.ZERO));

        ArgumentCaptor<CustomerLedgerEntry> captor = ArgumentCaptor.forClass(CustomerLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository).save(captor.capture());
        CustomerLedgerEntry saved = captor.getValue();
        assertThat(saved.getClientId()).isEqualTo("K1");
        assertThat(saved.getEntryType()).isEqualTo(LedgerEntryType.INVOICE_RAISED);
        assertThat(saved.getSourceId()).isEqualTo("I1");
        assertThat(saved.getDebitAmountInr()).isEqualByComparingTo("1000.00");
    }

    @Test
    void statementComputesARunningBalanceAndAnOpeningBalanceBeforeFrom() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        CustomerLedgerEntry beforeRange = entry(LocalDate.of(2026, 8, 1), LedgerEntryType.OPENING_BALANCE,
                new BigDecimal("500.00"), BigDecimal.ZERO);
        when(ledgerRepository.findByClientIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc("K1", LocalDate.of(2026, 9, 1)))
                .thenReturn(List.of(beforeRange));

        CustomerLedgerEntry raised = entry(LocalDate.of(2026, 9, 5), LedgerEntryType.INVOICE_RAISED,
                new BigDecimal("1000.00"), BigDecimal.ZERO);
        CustomerLedgerEntry received = entry(LocalDate.of(2026, 9, 10), LedgerEntryType.PAYMENT_RECEIVED,
                BigDecimal.ZERO, new BigDecimal("400.00"));
        when(ledgerRepository.findByClientIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(
                "K1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(raised, received));

        LedgerStatementResponse response = customerLedgerService.statement("K1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(response.getOpeningBalanceInr()).isEqualByComparingTo("500.00");
        assertThat(response.getEntries()).hasSize(2);
        assertThat(response.getEntries().get(0).getRunningBalanceInr()).isEqualByComparingTo("1500.00");
        assertThat(response.getEntries().get(1).getRunningBalanceInr()).isEqualByComparingTo("1100.00");
        assertThat(response.getClosingBalanceInr()).isEqualByComparingTo("1100.00");
    }

    @Test
    void summaryNetsAReversalAgainstItsOriginalReceipt() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        CustomerLedgerEntry raised = entry(LocalDate.of(2026, 9, 1), LedgerEntryType.INVOICE_RAISED,
                new BigDecimal("1000.00"), BigDecimal.ZERO);
        CustomerLedgerEntry received = entry(LocalDate.of(2026, 9, 2), LedgerEntryType.PAYMENT_RECEIVED,
                BigDecimal.ZERO, new BigDecimal("400.00"));
        CustomerLedgerEntry reversedReceipt = entry(LocalDate.of(2026, 9, 3), LedgerEntryType.PAYMENT_RECEIVED,
                new BigDecimal("400.00"), BigDecimal.ZERO);
        when(ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc("K1"))
                .thenReturn(List.of(raised, received, reversedReceipt));

        ClientLedgerSummaryResponse summary = customerLedgerService.summary("K1");

        assertThat(summary.getBilledInr()).isEqualByComparingTo("1000.00");
        assertThat(summary.getReceivedInr()).isEqualByComparingTo("0.00");
        assertThat(summary.getOutstandingInr()).isEqualByComparingTo("1000.00");
        assertThat(summary.getAdvanceInr()).isEqualByComparingTo("0.00");
    }

    @Test
    void outstandingBucketsByDaysPastDueDate() {
        LocalDate today = LocalDate.now();
        Invoice current = Invoice.builder().id("I1").clientId("K1").clientName("Arjun Mehta")
                .documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.ISSUED)
                .dueDate(today.plusDays(5)).balanceDueInr(new BigDecimal("100.00")).build();
        Invoice overdue45 = Invoice.builder().id("I2").clientId("K1").clientName("Arjun Mehta")
                .documentType(InvoiceDocumentType.TAX_INVOICE).status(InvoiceLifecycle.PARTIALLY_PAID)
                .dueDate(today.minusDays(45)).balanceDueInr(new BigDecimal("200.00")).build();
        when(invoiceRepository.findByDocumentTypeAndStatusInAndBalanceDueInrGreaterThan(any(), any(), any()))
                .thenReturn(List.of(current, overdue45));

        List<ArAgeingRowResponse> rows = customerLedgerService.outstanding();

        assertThat(rows).hasSize(1);
        ArAgeingRowResponse row = rows.get(0);
        assertThat(row.getClientId()).isEqualTo("K1");
        assertThat(row.getCurrent()).isEqualByComparingTo("100.00");
        assertThat(row.getDays31To60()).isEqualByComparingTo("200.00");
        assertThat(row.getTotalOutstandingInr()).isEqualByComparingTo("300.00");
    }

    @Test
    void openingBalancePostsADebitForAPositiveAmount() {
        when(clientService.findAccessibleClient("K1")).thenReturn(client());
        when(ledgerRepository.existsById(any())).thenReturn(false);
        when(ledgerRepository.findByClientIdOrderByEntryDateAscCreatedAtAsc("K1")).thenReturn(List.of());

        OpeningBalanceRequest request = new OpeningBalanceRequest();
        request.setAmount(new BigDecimal("15000.00"));
        request.setAsOfDate(LocalDate.of(2026, 4, 1));

        customerLedgerService.openingBalance("K1", request);

        ArgumentCaptor<CustomerLedgerEntry> captor = ArgumentCaptor.forClass(CustomerLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository).save(captor.capture());
        CustomerLedgerEntry saved = captor.getValue();
        assertThat(saved.getEntryType()).isEqualTo(LedgerEntryType.OPENING_BALANCE);
        assertThat(saved.getDebitAmountInr()).isEqualByComparingTo("15000.00");
        assertThat(saved.getCreditAmountInr()).isEqualByComparingTo("0.00");
    }
}
