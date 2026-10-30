package com.voyra.crm.repository.spec;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.models.BookingListFilter;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookingSpecificationsTest {

    private final CriteriaRecorder rec = new CriteriaRecorder();

    private String build(Specification<Booking> spec) {
        Predicate p = spec.toPredicate(rec.root(), rec.query, rec.cb);
        return rec.render(p);
    }

    @Test
    void agentSeesOwnAndServiceBookingsPlusTheServiceTypesTheyManage() {
        assertThat(build(BookingSpecifications.accessibleToAgent("A1", List.of(ServiceType.FLIGHT))))
                .isEqualTo("or(equal(root.agentId, A1), equal(root.serviceAgentId, A1), root.serviceType.in([FLIGHT]))");
    }

    @Test
    void agentWithNoManageableTypesSeesOnlyOwnAndServiceBookings() {
        assertThat(build(BookingSpecifications.accessibleToAgent("A1", List.of())))
                .isEqualTo("or(equal(root.agentId, A1), equal(root.serviceAgentId, A1))");
    }

    @Test
    void moneyPendingIsAnUnpaidLiveBookingOrACancelledOneWithARefundOpen() {
        String cancelled = "equal(root.bookingStatus, CANCELLED)";
        assertThat(build(BookingSpecifications.moneyPending())).isEqualTo(
                "or(and(not(" + cancelled + "), gt(root.sellingPrice, 0), or(equal(root.invoicedTotalInr, 0), "
                        + "lessThan(root.receivedTotalInr, root.invoicedTotalInr))), "
                        + "and(" + cancelled + ", or(lessThan(root.refundedTotalInr, root.customerRefundDue), "
                        + "lessThan(root.supplierRefundReceived, root.supplierRefundExpected), "
                        + "and(isNull(root.refundCheckedAt), or(gt(root.customerRefundDue, 0), gt(root.supplierRefundExpected, 0))))))");
    }

    @Test
    void simpleEqualityFilters() {
        assertThat(build(BookingSpecifications.typeIs(BookingType.HOTEL))).isEqualTo("equal(root.type, HOTEL)");
        assertThat(build(BookingSpecifications.statusIs(BookingStatus.CONFIRMED))).isEqualTo("equal(root.bookingStatus, CONFIRMED)");
        assertThat(build(BookingSpecifications.agentIs("A9"))).isEqualTo("equal(root.agentId, A9)");
        assertThat(build(BookingSpecifications.vendorIs("V9"))).isEqualTo("equal(root.vendorId, V9)");
    }

    @Test
    void destinationIsACaseInsensitiveSubstring() {
        assertThat(build(BookingSpecifications.destinationContains("GoA"))).isEqualTo("like(lower(root.destination), %goa%)");
    }

    @Test
    void aBareBkPrefixMatchesEverythingWhileTheNumberIsTyped() {
        assertThat(build(BookingSpecifications.searchMatches(" BK- "))).isEqualTo("conjunction()");
    }

    @Test
    void aBookingNumberMatchesExactlyAndAsThePrefixOfTheZeroPaddedNumber() {
        String p = build(BookingSpecifications.searchMatches("bk-0042"));
        assertThat(p).startsWith("or(equal(root.bookingNumber, 42), "
                + "like(function(lpad, String, root.bookingNumber.as(String), literal(8), literal(0)), 0042%), "
                + "like(lower(root.clientName), %bk-0042%, \\), ");
        assertThat(p).contains("like(lower(root.accountantRemark), %bk-0042%, \\)")
                .endsWith("exists(query.subquery(Long)), exists(query.subquery(Long)))");
        assertThat(rec.calls()).contains(
                "query.subquery(Long).select(literal(1)).where(equal(BookingDocument.bookingId, root.id), like(lower(BookingDocument.referenceNumber), %bk-0042%, \\))",
                "query.subquery(Long).select(literal(1)).where(equal(BookingPassenger.bookingId, root.id), like(lower(BookingPassenger.passengerName), %bk-0042%, \\))");
    }

    @Test
    void plainDigitsAreReadAsABookingNumberToo() {
        assertThat(build(BookingSpecifications.searchMatches("17"))).startsWith("or(equal(root.bookingNumber, 17), ");
    }

    @Test
    void freeTextMatchesTextFieldsWithLikeWildcardsEscaped() {
        String p = build(BookingSpecifications.searchMatches("  50%_off\\x "));
        assertThat(p).doesNotContain("root.bookingNumber")
                .startsWith("or(like(lower(root.clientName), %50\\%\\_off\\\\x%, \\), like(lower(root.destination), ");
        for (String field : List.of("pnr", "ticketNo", "hotelConfirmationNo", "visaApplicationNo", "transferVoucherNo", "notes")) {
            assertThat(p).contains("like(lower(root." + field + "), ");
        }
    }

    @Test
    void aBkPrefixFollowedByLettersIsFreeText() {
        assertThat(build(BookingSpecifications.searchMatches("bkx"))).doesNotContain("root.bookingNumber");
    }

    @Test
    void anEmptyFilterAddsNoPredicate() {
        assertThat(build(BookingSpecifications.matches(BookingListFilter.NONE, "INR"))).isEqualTo("and()");
    }

    @Test
    void blankTextFiltersAndAnUnknownRemarkAreIgnored() {
        BookingListFilter f = new BookingListFilter(null, null, null, null, " ", " ", " ", null, " ", "maybe");
        assertThat(build(BookingSpecifications.matches(f, "INR"))).isEqualTo("and()");
    }

    @Test
    void everyColumnFilterBecomesOnePredicate() {
        LocalDate d1 = LocalDate.of(2026, 1, 1), d2 = LocalDate.of(2026, 1, 31);
        BookingListFilter f = new BookingListFilter(d1, d2, d1, d2, "C1", "Business", " usd ", PaymentStatus.PAID, "U1", "has");
        assertThat(build(BookingSpecifications.matches(f, "INR"))).isEqualTo("and("
                + "greaterThanOrEqualTo(root.bookingDate, 2026-01-01), lessThanOrEqualTo(root.bookingDate, 2026-01-31), "
                + "greaterThanOrEqualTo(root.departureDate, 2026-01-01), lessThanOrEqualTo(root.departureDate, 2026-01-31), "
                + "equal(root.clientId, C1), equal(lower(root.flightCabin), business), equal(root.paymentStatus, PAID), "
                + "and(isNotNull(root.accountantRemark), notEqual(trim(root.accountantRemark), )), "
                + "exists(query.subquery(Long)), "
                + "or(root.leadId.in(query.subquery(String)), and(isNull(root.leadId), equal(root.createdBy, U1))))");
        assertThat(rec.calls()).contains(
                "query.subquery(Long).select(literal(1)).where(equal(BookingDocument.bookingId, root.id), equal(BookingDocument.purchaseCurrency, USD))",
                "query.subquery(String).select(Lead.id).where(equal(Lead.createdBy, U1))");
    }

    @Test
    void noRemarkAndBaseCurrencyMeanNothingBoughtAbroad() {
        BookingListFilter f = new BookingListFilter(null, null, null, null, null, null, "inr", null, null, "NONE");
        assertThat(build(BookingSpecifications.matches(f, "INR"))).isEqualTo("and("
                + "or(isNull(root.accountantRemark), equal(trim(root.accountantRemark), )), "
                + "not(exists(query.subquery(Long))))");
        assertThat(rec.calls()).contains(
                "query.subquery(Long).select(literal(1)).where(equal(BookingDocument.bookingId, root.id), isNotNull(BookingDocument.purchaseCurrency))");
    }

    @Test
    void unbilledIsConfirmedWithNoLiveInvoiceOnTheBookingOrAnyOfItsLines() {
        assertThat(build(BookingSpecifications.confirmedAndUnbilled()))
                .isEqualTo("and(equal(root.bookingStatus, CONFIRMED), not(exists(query.subquery(Long))))");
        assertThat(rec.calls()).contains(
                "query.subquery(Long).select(literal(1))",
                "query.subquery(String).select(InvoiceLineItem.invoiceId).where(equal(InvoiceLineItem.bookingId, root.id))",
                "query.subquery(Long).where(or(equal(Invoice.bookingId, root.id), Invoice.id.in(query.subquery(String))), notEqual(Invoice.status, CANCELLED))");
    }
}
