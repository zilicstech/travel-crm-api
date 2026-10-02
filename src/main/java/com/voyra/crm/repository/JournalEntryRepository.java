package com.voyra.crm.repository;

import com.voyra.crm.entity.JournalEntry;
import com.voyra.crm.enums.JournalPurpose;
import com.voyra.crm.enums.JournalSourceType;
import com.voyra.crm.enums.JournalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {

    Optional<JournalEntry> findBySourceTypeAndSourceIdAndPurpose(
            JournalSourceType sourceType, String sourceId, JournalPurpose purpose);

    boolean existsBySourceTypeAndSourceIdAndPurpose(
            JournalSourceType sourceType, String sourceId, JournalPurpose purpose);

    List<JournalEntry> findByEntryDateBetween(LocalDate from, LocalDate to);

    List<JournalEntry> findByStatus(JournalStatus status);
}
