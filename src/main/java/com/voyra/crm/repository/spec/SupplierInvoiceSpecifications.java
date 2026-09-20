package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

/**
 * Built as {@code Specification}s rather than a hand-written {@code (:param IS NULL OR ...)}
 * JPQL query - see {@code AuditLogRepository}'s javadoc for why that combination breaks Postgres
 * ("could not determine data type of parameter") once a {@code Pageable} is involved.
 */
public final class SupplierInvoiceSpecifications {

    private SupplierInvoiceSpecifications() {
    }

    public static Specification<SupplierInvoice> vendorIs(String vendorId) {
        return vendorId == null ? Specification.where(null)
                : (root, query, cb) -> cb.equal(root.get("vendorId"), vendorId);
    }

    public static Specification<SupplierInvoice> statusIs(SupplierInvoiceStatus status) {
        return status == null ? Specification.where(null)
                : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<SupplierInvoice> bookingIs(String bookingId) {
        return bookingId == null ? Specification.where(null)
                : (root, query, cb) -> cb.equal(root.get("bookingId"), bookingId);
    }

    public static Specification<SupplierInvoice> dueBefore(LocalDate date) {
        return date == null ? Specification.where(null)
                : (root, query, cb) -> cb.lessThan(root.get("dueDate"), date);
    }

    /** vendor_id IS NULL - a legacy row that migrated with no matching vendor, surfaced for repointing. */
    public static Specification<SupplierInvoice> unlinked() {
        return (root, query, cb) -> cb.isNull(root.get("vendorId"));
    }
}
