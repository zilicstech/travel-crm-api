package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.SupplierInvoiceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierInvoiceSpecificationsTest {

    private final CriteriaRecorder rec = new CriteriaRecorder();

    private String build(Specification<SupplierInvoice> spec) {
        return rec.render(spec.toPredicate(rec.root(), rec.query, rec.cb));
    }

    @Test
    void aSuppliedFilterAddsItsPredicate() {
        assertThat(build(SupplierInvoiceSpecifications.vendorIs("V1"))).isEqualTo("equal(root.vendorId, V1)");
        assertThat(build(SupplierInvoiceSpecifications.statusIs(SupplierInvoiceStatus.values()[0])))
                .isEqualTo("equal(root.status, " + SupplierInvoiceStatus.values()[0] + ")");
        assertThat(build(SupplierInvoiceSpecifications.bookingIs("B1"))).isEqualTo("equal(root.bookingId, B1)");
        assertThat(build(SupplierInvoiceSpecifications.dueBefore(LocalDate.of(2026, 3, 1)))).isEqualTo("lessThan(root.dueDate, 2026-03-01)");
        assertThat(build(SupplierInvoiceSpecifications.unlinked())).isEqualTo("isNull(root.vendorId)");
    }

    @Test
    void aMissingFilterAddsNoPredicate() {
        assertThat(build(SupplierInvoiceSpecifications.vendorIs(null))).isEqualTo("null");
        assertThat(build(SupplierInvoiceSpecifications.statusIs(null))).isEqualTo("null");
        assertThat(build(SupplierInvoiceSpecifications.bookingIs(null))).isEqualTo("null");
        assertThat(build(SupplierInvoiceSpecifications.dueBefore(null))).isEqualTo("null");
        assertThat(rec.calls()).isEmpty();
    }
}
