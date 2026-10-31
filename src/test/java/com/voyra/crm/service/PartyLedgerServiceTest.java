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

    @Test
    void aNullTypeMeansAllWithOpenDatesAndRowsWithoutBookingsOrVendorsNeedNoLookups() {
        when(customerLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(LocalDate.of(2000, 1, 1), LocalDate.of(2999, 12, 31)))
                .thenReturn(List.of(CustomerLedgerEntry.builder().clientId("C1").entryDate(LocalDate.of(2026, 10, 5))
                        .entryType(LedgerEntryType.PAYMENT_RECEIVED).debitAmountInr(null).creditAmountInr(null).build()));
        when(supplierLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(LocalDate.of(2000, 1, 1), LocalDate.of(2999, 12, 31)))
                .thenReturn(List.of(SupplierLedgerEntry.builder().vendorId(null).entryDate(LocalDate.of(2026, 10, 1))
                        .entryType(SupplierLedgerEntryType.BILL_BOOKED).creditAmountInr(d("5")).build()));

        PartyLedgerResponse r = service.entries(null, " ", null, null);

        assertThat(r.getPartyType()).isEqualTo(LedgerPartyType.ALL);
        assertThat(r.getEntries()).hasSize(2);
        assertThat(r.getEntries().get(1).getDebitInr()).isEqualByComparingTo("0");
        assertThat(r.getEntries().get(1).getCreditInr()).isEqualByComparingTo("0");
        assertThat(r.getTotalCreditInr()).isEqualByComparingTo("5");
        org.mockito.Mockito.verifyNoInteractions(bookingRepository);
    }

    @Test
    void customersOnlyOrSuppliersOnlyReadOneSide() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        when(customerLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(from, LocalDate.of(2999, 12, 31))).thenReturn(List.of());
        when(supplierLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(LocalDate.of(2000, 1, 1), from)).thenReturn(List.of());

        assertThat(service.entries(LedgerPartyType.CUSTOMER, null, from, null).getEntries()).isEmpty();
        assertThat(service.entries(LedgerPartyType.SUPPLIER, null, null, from).getEntries()).isEmpty();
    }

    @Test
    void oneSupplierIsTheirStatementWithBookingRefs() {
        when(supplierLedgerService.statement("V1", null, null)).thenReturn(com.voyra.crm.dto.SupplierLedgerStatementResponse.builder()
                .vendorId("V1").vendorName("TripJack").openingBalanceInr(d("0")).closingBalanceInr(d("-500"))
                .entries(List.of(com.voyra.crm.dto.SupplierLedgerEntryResponse.builder().entryDate(LocalDate.of(2026, 10, 5))
                        .entryType(SupplierLedgerEntryType.BILL_BOOKED).bookingId("B1").creditAmountInr(d("500")).runningBalanceInr(d("-500")).build()))
                .build());
        when(bookingRepository.findAllById(List.of("B1"))).thenReturn(List.of(Booking.builder().id("B1").bookingNumber(7L).build()));

        PartyLedgerResponse r = service.entries(LedgerPartyType.SUPPLIER, "V1", null, null);

        assertThat(r.getPartyName()).isEqualTo("TripJack");
        assertThat(r.getEntries().get(0).getBookingRef()).isEqualTo("BK00000007");
        assertThat(r.getClosingBalanceInr()).isEqualByComparingTo("-500");
    }

    @Test
    void sameDayDatesAreFine() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        when(customerLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(day, day)).thenReturn(List.of());
        when(supplierLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(day, day)).thenReturn(List.of());

        assertThat(service.entries(LedgerPartyType.ALL, null, day, day).getEntries()).isEmpty();
    }
}
