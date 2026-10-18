package com.voyra.crm.service;

import com.voyra.crm.dto.ClientCreditResponse;
import com.voyra.crm.entity.Client;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.BookingNextStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A client's credit limit in use. Nothing here is stored: used is worked out every time from the
 * customer ledger (invoices, payments, credit notes, refunds), the live bookings not yet invoiced,
 * and the charges kept on bookings cancelled before invoicing - so no one ever adjusts it by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientCreditService {

    private final ClientRepository clientRepository;
    private final BookingRepository bookingRepository;
    private final CustomerLedgerEntryRepository ledgerRepository;

    @Transactional(readOnly = true)
    public ClientCreditResponse credit(Client client) {
        return creditFor(List.of(client)).get(client.getId());
    }

    /** One set of grouped queries for a whole page of clients - never one round of queries per row. */
    @Transactional(readOnly = true)
    public Map<String, ClientCreditResponse> creditFor(Collection<Client> clients) {
        if (clients.isEmpty()) return Map.of();
        List<String> ids = clients.stream().map(Client::getId).toList();
        Map<String, BigDecimal> ledger = sums(ledgerRepository.balanceInrByClient(ids));
        Map<String, BigDecimal> booked = sums(bookingRepository.liveSellingPriceByClient(ids));
        Map<String, BigDecimal> invoiced = sums(bookingRepository.liveInvoicedInrByClient(ids));
        Map<String, BigDecimal> charges = sums(bookingRepository.uninvoicedCancellationChargesByClient(ids));

        Map<String, ClientCreditResponse> out = new HashMap<>();
        for (Client c : clients) {
            BigDecimal balance = ledger.getOrDefault(c.getId(), BigDecimal.ZERO);
            BigDecimal notInvoiced = booked.getOrDefault(c.getId(), BigDecimal.ZERO)
                    .subtract(invoiced.getOrDefault(c.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            BigDecimal kept = charges.getOrDefault(c.getId(), BigDecimal.ZERO);
            BigDecimal used = balance.add(notInvoiced).add(kept);
            out.put(c.getId(), ClientCreditResponse.builder()
                    .limit(c.getCreditLimit())
                    .invoicedUnpaid(balance.max(BigDecimal.ZERO))
                    .bookedNotInvoiced(notInvoiced)
                    .cancellationCharges(kept)
                    .advanceHeld(balance.min(BigDecimal.ZERO).negate())
                    .used(used)
                    .available(c.getCreditLimit() == null ? null : c.getCreditLimit().subtract(used))
                    .build());
        }
        return out;
    }

    /**
     * Refuses an increase of {@code increase} in what the client owes when it would take them over
     * their limit. Only the owner can pass {@code override}. Call it right before the increase is
     * applied: the flush makes any reduction already made in this transaction (a voucher being
     * re-priced) count, so it is never double-counted.
     */
    public void assertWithinLimit(String clientId, BigDecimal increase, boolean override) {
        if (clientId == null || increase == null || increase.signum() <= 0) return;
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null || client.getCreditLimit() == null) return;
        bookingRepository.flush();
        BigDecimal available = credit(client).getAvailable();
        BigDecimal over = increase.subtract(available);
        if (over.signum() <= 0) return;
        if (override && SecurityContextUtil.getCurrentUser().map(u -> u.isAgencyOwner()).orElse(false)) {
            log.warn("Credit limit overridden by owner: clientId={}, over={}", clientId, over);
            return;
        }
        throw new IllegalStateException("This takes " + client.getName() + " " + BookingNextStep.rupees(over)
                + " over their credit limit (" + BookingNextStep.rupees(available.max(BigDecimal.ZERO))
                + " available). Ask the owner to raise the limit.");
    }

    private static Map<String, BigDecimal> sums(List<Object[]> rows) {
        return rows.stream().filter(r -> r[1] != null).collect(Collectors.toMap(
                r -> (String) r[0], r -> new BigDecimal(r[1].toString()), BigDecimal::add));
    }
}
