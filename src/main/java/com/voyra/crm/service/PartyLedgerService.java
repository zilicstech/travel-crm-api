package com.voyra.crm.service;

import com.voyra.crm.dto.LedgerStatementResponse;
import com.voyra.crm.dto.PartyLedgerEntryResponse;
import com.voyra.crm.dto.PartyLedgerResponse;
import com.voyra.crm.dto.SupplierLedgerStatementResponse;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.CustomerLedgerEntry;
import com.voyra.crm.entity.SupplierLedgerEntry;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.enums.LedgerPartyType;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.CustomerLedgerEntryRepository;
import com.voyra.crm.repository.SupplierLedgerEntryRepository;
import com.voyra.crm.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Ledger screen: the customer and supplier subsidiary ledgers behind one filter (party type,
 * party, dates). One party reuses its own statement - opening, running and closing balances; a
 * whole party type, or both, lists every row in the range with totals, since one running balance
 * across different parties means nothing. Customer balances are receivable (Dr - Cr), supplier
 * balances payable (Cr - Dr), the same signs as their own statements.
 */
@Service
@RequiredArgsConstructor
public class PartyLedgerService {

    private final CustomerLedgerService customerLedgerService;
    private final SupplierLedgerService supplierLedgerService;
    private final CustomerLedgerEntryRepository customerLedgerRepository;
    private final SupplierLedgerEntryRepository supplierLedgerRepository;
    private final ClientRepository clientRepository;
    private final VendorRepository vendorRepository;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public PartyLedgerResponse entries(LedgerPartyType partyType, String partyId, LocalDate from, LocalDate to) {
        LedgerPartyType type = partyType != null ? partyType : LedgerPartyType.ALL;
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("From must be on or before To");
        }
        if (partyId != null && !partyId.isBlank()) {
            if (type == LedgerPartyType.ALL) {
                throw new IllegalArgumentException("Pick Customers or Suppliers to see one party's ledger");
            }
            return type == LedgerPartyType.CUSTOMER ? customer(partyId, from, to) : supplier(partyId, from, to);
        }

        LocalDate start = from != null ? from : LocalDate.of(2000, 1, 1);
        LocalDate end = to != null ? to : LocalDate.of(2999, 12, 31);
        List<PartyLedgerEntryResponse> rows = new ArrayList<>();
        if (type != LedgerPartyType.SUPPLIER) {
            List<CustomerLedgerEntry> entries = customerLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(start, end);
            Map<String, String> names = new HashMap<>();
            clientRepository.findAllById(entries.stream().map(CustomerLedgerEntry::getClientId).distinct().toList())
                    .forEach(c -> names.put(c.getId(), c.getName()));
            entries.forEach(e -> rows.add(row(LedgerPartyType.CUSTOMER, e.getClientId(), names.get(e.getClientId()), e.getEntryDate(),
                    e.getEntryType().name(), e.getDocumentNumber(), e.getNarration(), e.getBookingId(),
                    e.getDebitAmountInr(), e.getCreditAmountInr(), null, e.getCreatedAt())));
        }
        if (type != LedgerPartyType.CUSTOMER) {
            List<SupplierLedgerEntry> entries = supplierLedgerRepository.findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(start, end);
            Map<String, String> names = new HashMap<>();
            vendorRepository.findAllById(entries.stream().map(SupplierLedgerEntry::getVendorId).filter(Objects::nonNull).distinct().toList())
                    .forEach(v -> names.put(v.getId(), v.getName()));
            entries.forEach(e -> rows.add(row(LedgerPartyType.SUPPLIER, e.getVendorId(), names.get(e.getVendorId()), e.getEntryDate(),
                    e.getEntryType().name(), e.getDocumentNumber(), e.getNarration(), e.getBookingId(),
                    e.getDebitAmountInr(), e.getCreditAmountInr(), null, e.getCreatedAt())));
        }
        rows.sort(Comparator.comparing(PartyLedgerEntryResponse::getEntryDate));
        addBookingRefs(rows);
        return PartyLedgerResponse.builder().partyType(type).from(from).to(to)
                .totalDebitInr(sum(rows, PartyLedgerEntryResponse::getDebitInr))
                .totalCreditInr(sum(rows, PartyLedgerEntryResponse::getCreditInr))
                .entries(rows).build();
    }

    private PartyLedgerResponse customer(String clientId, LocalDate from, LocalDate to) {
        LedgerStatementResponse s = customerLedgerService.statement(clientId, from, to);
        List<PartyLedgerEntryResponse> rows = s.getEntries().stream()
                .map(e -> row(LedgerPartyType.CUSTOMER, clientId, s.getClientName(), e.getEntryDate(), e.getEntryType().name(),
                        e.getDocumentNumber(), e.getNarration(), e.getBookingId(), e.getDebitAmountInr(), e.getCreditAmountInr(),
                        e.getRunningBalanceInr(), e.getCreatedAt()))
                .toList();
        addBookingRefs(rows);
        return single(LedgerPartyType.CUSTOMER, clientId, s.getClientName(), from, to, s.getOpeningBalanceInr(), s.getClosingBalanceInr(), rows);
    }

    private PartyLedgerResponse supplier(String vendorId, LocalDate from, LocalDate to) {
        SupplierLedgerStatementResponse s = supplierLedgerService.statement(vendorId, from, to);
        List<PartyLedgerEntryResponse> rows = s.getEntries().stream()
                .map(e -> row(LedgerPartyType.SUPPLIER, vendorId, s.getVendorName(), e.getEntryDate(), e.getEntryType().name(),
                        e.getDocumentNumber(), e.getNarration(), e.getBookingId(), e.getDebitAmountInr(), e.getCreditAmountInr(),
                        e.getRunningBalanceInr(), e.getCreatedAt()))
                .toList();
        addBookingRefs(rows);
        return single(LedgerPartyType.SUPPLIER, vendorId, s.getVendorName(), from, to, s.getOpeningBalanceInr(), s.getClosingBalanceInr(), rows);
    }

    private static PartyLedgerResponse single(LedgerPartyType type, String id, String name, LocalDate from, LocalDate to,
                                              BigDecimal opening, BigDecimal closing, List<PartyLedgerEntryResponse> rows) {
        return PartyLedgerResponse.builder().partyType(type).partyId(id).partyName(name).from(from).to(to)
                .openingBalanceInr(opening).closingBalanceInr(closing)
                .totalDebitInr(sum(rows, PartyLedgerEntryResponse::getDebitInr))
                .totalCreditInr(sum(rows, PartyLedgerEntryResponse::getCreditInr))
                .entries(rows).build();
    }

    private static PartyLedgerEntryResponse row(LedgerPartyType type, String partyId, String partyName, LocalDate date,
                                                String entryType, String document, String narration, String bookingId,
                                                BigDecimal debit, BigDecimal credit, BigDecimal running, java.time.LocalDateTime createdAt) {
        return PartyLedgerEntryResponse.builder().partyType(type).partyId(partyId).partyName(partyName)
                .entryDate(date).entryType(entryType).documentNumber(document).narration(narration).bookingId(bookingId)
                .debitInr(debit != null ? debit : BigDecimal.ZERO).creditInr(credit != null ? credit : BigDecimal.ZERO)
                .runningBalanceInr(running).build();
    }

    /** One query for the page's bookings, so a row can say BK-00042 and link to it. */
    private void addBookingRefs(List<PartyLedgerEntryResponse> rows) {
        List<String> ids = rows.stream().map(PartyLedgerEntryResponse::getBookingId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return;
        Map<String, String> refs = new HashMap<>();
        for (Booking b : bookingRepository.findAllById(ids)) {
            refs.put(b.getId(), BookingService.bookingRef(b.getBookingNumber()));
        }
        rows.forEach(r -> r.setBookingRef(refs.get(r.getBookingId())));
    }

    private static BigDecimal sum(List<PartyLedgerEntryResponse> rows, java.util.function.Function<PartyLedgerEntryResponse, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
