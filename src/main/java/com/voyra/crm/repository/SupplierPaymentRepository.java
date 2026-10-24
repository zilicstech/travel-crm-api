package com.voyra.crm.repository;

import com.voyra.crm.entity.SupplierPayment;
import com.voyra.crm.enums.SupplierPaymentDirection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, String>, JpaSpecificationExecutor<SupplierPayment> {

    /** Row lock so one payment can't be reversed twice at once. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupplierPayment s where s.id = :id")
    java.util.Optional<SupplierPayment> findByIdForUpdate(@Param("id") String id);

    List<SupplierPayment> findBySupplierInvoiceIdOrderByPaidOnAscCreatedAtAsc(String supplierInvoiceId);

    /** Advances paid to a vendor - SupplierPaymentService#applyAdvance sums these against applied-from-advance rows to find the remaining pool. */
    List<SupplierPayment> findByVendorIdAndIsAdvanceTrueOrderByPaidOnAsc(String vendorId);

    /** Rows that only moved a bill's balance, no real cash - see ARCHITECTURE-SPINE AD-5. */
    List<SupplierPayment> findByVendorIdAndAppliedFromAdvanceTrueOrderByPaidOnAsc(String vendorId);

    List<SupplierPayment> findByDirectionAndPaidOnBetween(SupplierPaymentDirection direction, LocalDate from, LocalDate to);

    boolean existsByBookingId(String bookingId);

    List<SupplierPayment> findByBookingId(String bookingId);

    boolean existsBySupplierInvoiceId(String supplierInvoiceId);

    boolean existsByBankAccountId(String bankAccountId);

    /** Live applications of advances to a vendor's bills (reversal rows included - callers skip those). */
    List<SupplierPayment> findByVendorIdAndAppliedFromAdvanceTrueAndReversedAtIsNull(String vendorId);
}
