package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SupplierLedgerEntryRepository extends JpaRepository<SupplierLedgerEntry, String> {

    List<SupplierLedgerEntry> findByVendorIdOrderByEntryDateAscCreatedAtAsc(String vendorId);

    List<SupplierLedgerEntry> findByVendorIdAndEntryDateLessThanOrderByEntryDateAscCreatedAtAsc(String vendorId, LocalDate before);

    List<SupplierLedgerEntry> findByVendorIdAndEntryDateBetweenOrderByEntryDateAscCreatedAtAsc(String vendorId, LocalDate from, LocalDate to);

    boolean existsByBookingId(String bookingId);
}
