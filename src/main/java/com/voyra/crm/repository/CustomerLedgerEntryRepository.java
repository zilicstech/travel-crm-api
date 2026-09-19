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
}
