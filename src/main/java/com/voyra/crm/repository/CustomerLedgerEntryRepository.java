package com.voyra.crm.repository;

import com.voyra.crm.entity.CustomerLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CustomerLedgerEntryRepository extends JpaRepository<CustomerLedgerEntry, String> {

    List<CustomerLedgerEntry> findByClientIdOrderByEntryDateAscCreatedAtAsc(String clientId);

    List<CustomerLedgerEntry> findByClientIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc(String clientId, LocalDate before);

    List<CustomerLedgerEntry> findByClientIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(String clientId, LocalDate from, LocalDate to);

    boolean existsByBookingId(String bookingId);

    /** [clientId, Σ debit − credit in INR] - each client's running balance, for the credit limit. */
    @org.springframework.data.jpa.repository.Query("SELECT e.clientId, SUM(e.debitAmountInr - e.creditAmountInr) FROM CustomerLedgerEntry e "
            + "WHERE e.clientId IN :clientIds GROUP BY e.clientId")
    List<Object[]> balanceInrByClient(@org.springframework.data.repository.query.Param("clientIds") java.util.Collection<String> clientIds);

    List<CustomerLedgerEntry> findByEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(LocalDate from, LocalDate to);
}
