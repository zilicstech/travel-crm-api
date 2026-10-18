package com.voyra.crm.service;

import com.voyra.crm.dto.ClientCreditResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ClientCreditServiceTest {

    @Mock private ClientRepository clientRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CustomerLedgerEntryRepository ledgerRepository;
    @Mock private AuditService auditService;
    @InjectMocks private ClientCreditService service;

    private final Client client = Client.builder().id("C1").name("Sharma Family").creditLimit(new BigDecimal("100000")).build();

    private static List<Object[]> row(String amount) {
        return amount == null ? List.of() : List.<Object[]>of(new Object[] { "C1", new BigDecimal(amount) });
    }

    /** ledger balance, live selling price, live invoiced, kept cancellation charges */
    private void given(String ledger, String booked, String invoiced, String charges) {
        lenient().when(ledgerRepository.balanceInrByClient(any())).thenReturn(row(ledger));
        lenient().when(bookingRepository.liveSellingPriceByClient(any())).thenReturn(row(booked));
        lenient().when(bookingRepository.liveInvoicedInrByClient(any())).thenReturn(row(invoiced));
        lenient().when(bookingRepository.uninvoicedCancellationChargesByClient(any())).thenReturn(row(charges));
        lenient().when(clientRepository.findById("C1")).thenReturn(Optional.of(client));
    }

    private static void as(UserType role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("U1", "u", role, "T1"), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usedSumsLedgerUninvoicedAndKeptCharges() {
        // 30k invoiced and unpaid; 50k booked of which 20k is invoiced; 5k kept on a cancellation.
        given("30000", "50000", "20000", "5000");
        ClientCreditResponse c = service.credit(client);
        assertThat(c.getInvoicedUnpaid()).isEqualByComparingTo("30000");
        assertThat(c.getBookedNotInvoiced()).isEqualByComparingTo("30000");
        assertThat(c.getCancellationCharges()).isEqualByComparingTo("5000");
        assertThat(c.getUsed()).isEqualByComparingTo("65000");
        assertThat(c.getAvailable()).isEqualByComparingTo("35000");
    }

    @Test
    void advanceMakesUsedNegative() {
        given("-25000", null, null, null);
        ClientCreditResponse c = service.credit(client);
        assertThat(c.getAdvanceHeld()).isEqualByComparingTo("25000");
        assertThat(c.getUsed()).isEqualByComparingTo("-25000");
        assertThat(c.getAvailable()).isEqualByComparingTo("125000");
    }

    @Test
    void nullLimitNeverBlocks() {
        Client open = Client.builder().id("C2").name("Open").build();
        lenient().when(clientRepository.findById("C2")).thenReturn(Optional.of(open));
        as(UserType.AGENT);
        assertThatCode(() -> service.assertWithinLimit("C2", new BigDecimal("99999999"), false)).doesNotThrowAnyException();
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void agentOverLimitIsRefusedWithMessage() {
        given("0", "80000", null, null);
        as(UserType.AGENT);
        assertThatThrownBy(() -> service.assertWithinLimit("C1", new BigDecimal("30000"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This takes Sharma Family ₹10,000 over their credit limit (₹20,000 available). Ask the owner to raise the limit.");
    }

    @Test
    void withinLimitPasses() {
        given("0", "80000", null, null);
        as(UserType.AGENT);
        assertThatCode(() -> service.assertWithinLimit("C1", new BigDecimal("20000"), false)).doesNotThrowAnyException();
    }

    @Test
    void ownerOverrideAllowedButOwnerWithoutOverrideRefused() {
        given("0", "80000", null, null);
        as(UserType.AGENCY_OWNER);
        assertThatThrownBy(() -> service.assertWithinLimit("C1", new BigDecimal("30000"), false))
                .hasMessageContaining("over their credit limit");
        assertThatCode(() -> service.assertWithinLimit("C1", new BigDecimal("30000"), true)).doesNotThrowAnyException();
        // The override is on the client's audit trail, not only in the app log.
        org.mockito.Mockito.verify(auditService).recordUpdate(org.mockito.ArgumentMatchers.eq(com.voyra.crm.enums.AuditEntityType.CLIENT),
                org.mockito.ArgumentMatchers.eq("C1"), any(), org.mockito.ArgumentMatchers.argThat(c -> c.get(0).getField().equals("creditLimitOverride")));
    }

    @Test
    void figuresAreRoundedToPaise() {
        given("0", "1000", "950.00000000", null);
        assertThat(service.credit(client).getBookedNotInvoiced()).hasToString("50.00");
    }

    @Test
    void aDecreaseIsNeverBlocked() {
        as(UserType.AGENT);
        assertThatCode(() -> service.assertWithinLimit("C1", new BigDecimal("-5000"), false)).doesNotThrowAnyException();
        verifyNoInteractions(clientRepository);
    }
}
