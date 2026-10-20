package com.voyra.crm.service;

import com.voyra.crm.dto.LedgerEntryResponse;
import com.voyra.crm.dto.LedgerStatementResponse;
import com.voyra.crm.dto.PartyLedgerResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.CustomerLedgerEntry;
import com.voyra.crm.entity.SupplierLedgerEntry;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.LedgerEntryType;
import com.voyra.crm.enums.LedgerPartyType;
import com.voyra.crm.enums.SupplierLedgerEntryType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.VendorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyLedgerServiceTest {

    @Mock private CustomerLedgerService customerLedgerService;
    @Mock private SupplierLedgerService supplierLedgerService;
    @Mock private CustomerLedgerEntryRepository customerLedgerRepository;
    @Mock private SupplierLedgerEntryRepository supplierLedgerRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private VendorRepository vendorRepository;
    @Mock private BookingRepository bookingRepository;
    @InjectMocks private PartyLedgerService service;

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void allPartiesListsCustomerAndSupplierRowsByDateWithTotalsAndBookingRefs() {
        LocalDate from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 31);
        when(customerLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(from, to)).thenReturn(List.of(
                CustomerLedgerEntry.builder().clientId("C1").entryDate(LocalDate.of(2026, 10, 5)).entryType(LedgerEntryType.INVOICE_RAISED)
                        .bookingId("B1").debitAmountInr(d("12000")).creditAmountInr(d("0")).build()));
        when(supplierLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(from, to)).thenReturn(List.of(
                SupplierLedgerEntry.builder().vendorId("V1").entryDate(LocalDate.of(2026, 10, 2)).entryType(SupplierLedgerEntryType.BILL_BOOKED)
                        .debitAmountInr(d("0")).creditAmountInr(d("10000")).build()));
        when(clientRepository.findAllById(any())).thenReturn(List.of(Client.builder().id("C1").name("Sharma Family").build()));
        when(vendorRepository.findAllById(any())).thenReturn(List.of(Vendor.builder().id("V1").name("TripJack").build()));
        when(bookingRepository.findAllById(any())).thenReturn(List.of(Booking.builder().id("B1").bookingNumber(42L).build()));

        PartyLedgerResponse r = service.entries(LedgerPartyType.ALL, null, from, to);

        assertThat(r.getEntries()).extracting(e -> e.getPartyName()).containsExactly("TripJack", "Sharma Family");
        assertThat(r.getEntries().get(1).getBookingRef()).isEqualTo("BK00000042");
        assertThat(r.getTotalDebitInr()).isEqualByComparingTo("12000");
        assertThat(r.getTotalCreditInr()).isEqualByComparingTo("10000");
        assertThat(r.getOpeningBalanceInr()).isNull();
    }

    @Test
    void oneCustomerIsTheirStatementWithBalances() {
        when(customerLedgerService.statement("C1", null, null)).thenReturn(LedgerStatementResponse.builder()
                .clientId("C1").clientName("Sharma Family").openingBalanceInr(d("0")).closingBalanceInr(d("12000"))
                .entries(List.of(LedgerEntryResponse.builder().entryDate(LocalDate.of(2026, 10, 5)).entryType(LedgerEntryType.INVOICE_RAISED)
                        .debitAmountInr(d("12000")).creditAmountInr(d("0")).runningBalanceInr(d("12000")).build()))
                .build());

        PartyLedgerResponse r = service.entries(LedgerPartyType.CUSTOMER, "C1", null, null);

        assertThat(r.getPartyName()).isEqualTo("Sharma Family");
        assertThat(r.getClosingBalanceInr()).isEqualByComparingTo("12000");
        assertThat(r.getEntries().get(0).getRunningBalanceInr()).isEqualByComparingTo("12000");
    }

    @Test
    void aPartyNeedsAPartyTypeAndDatesMustBeInOrder() {
        assertThatThrownBy(() -> service.entries(LedgerPartyType.ALL, "C1", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.entries(LedgerPartyType.ALL, null, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
