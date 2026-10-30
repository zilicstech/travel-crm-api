package com.voyra.crm.entity;

import com.voyra.crm.enums.KycDocumentStatus;
import com.voyra.crm.enums.TenantStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-written JPA callbacks: each stamps its timestamps (and defaults) only when the caller
 * left them empty, so an imported or back-dated row keeps the value it was given.
 */
class EntityLifecycleCallbacksTest {

    private static final LocalDateTime OLD = LocalDateTime.of(2020, 1, 2, 3, 4);
    private static final LocalDate OLD_DATE = LocalDate.of(2020, 1, 2);
    private static final Instant OLD_INSTANT = Instant.parse("2020-01-02T03:04:00Z");

    /** Runs onCreate on an empty entity (expects a fresh stamp) and on a pre-filled one (expects the old value kept). */
    private static <T> void stampsOnlyWhenEmpty(Supplier<T> empty, Supplier<T> filled, Consumer<T> onCreate,
                                                Function<T, Temporal> field, Temporal old) {
        T fresh = empty.get();
        onCreate.accept(fresh);
        assertThat(field.apply(fresh)).isNotNull().isNotEqualTo(old);

        T kept = filled.get();
        onCreate.accept(kept);
        assertThat(field.apply(kept)).isEqualTo(old);
    }

    @Test
    void createdAtIsStampedOnlyWhenMissing() {
        stampsOnlyWhenEmpty(AgencySetting::new, () -> AgencySetting.builder().createdAt(OLD).build(), AgencySetting::onCreate, AgencySetting::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(AuditLog::new, () -> AuditLog.builder().createdAt(OLD).build(), AuditLog::onCreate, AuditLog::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(Client::new, () -> Client.builder().createdAt(OLD).build(), Client::onCreate, Client::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(CommunicationLog::new, () -> CommunicationLog.builder().createdAt(OLD).build(), CommunicationLog::onCreate, CommunicationLog::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(Lead::new, () -> Lead.builder().createdAt(OLD).build(), Lead::onCreate, Lead::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(LeadFollowUp::new, () -> LeadFollowUp.builder().createdAt(OLD).build(), LeadFollowUp::onCreate, LeadFollowUp::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(LeadMember::new, () -> LeadMember.builder().createdAt(OLD).build(), LeadMember::onCreate, LeadMember::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(LeadNote::new, () -> LeadNote.builder().createdAt(OLD).build(), LeadNote::onCreate, LeadNote::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(LeadProposal::new, () -> LeadProposal.builder().createdAt(OLD).build(), LeadProposal::onCreate, LeadProposal::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(LeadService::new, () -> LeadService.builder().createdAt(OLD).build(), LeadService::onCreate, LeadService::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(LeadTimeline::new, () -> LeadTimeline.builder().createdAt(OLD).build(), LeadTimeline::onCreate, LeadTimeline::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(Member::new, () -> Member.builder().createdAt(OLD).build(), Member::onCreate, Member::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(Vendor::new, () -> Vendor.builder().createdAt(OLD).build(), Vendor::onCreate, Vendor::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(Inquiry::new, () -> Inquiry.builder().createdAt(OLD_INSTANT).build(), Inquiry::onCreate, Inquiry::getCreatedAt, OLD_INSTANT);
        stampsOnlyWhenEmpty(PlatformAuditLog::new, () -> PlatformAuditLog.builder().createdAt(OLD_INSTANT).build(), PlatformAuditLog::onCreate, PlatformAuditLog::getCreatedAt, OLD_INSTANT);
    }

    @Test
    void createdDateIsStampedOnlyWhenMissing() {
        stampsOnlyWhenEmpty(Agent::new, () -> Agent.builder().createdDate(OLD).build(), Agent::onCreate, Agent::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(FeedbackLink::new, () -> FeedbackLink.builder().createdDate(OLD).build(), FeedbackLink::onCreate, FeedbackLink::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(PlatformAdmin::new, () -> PlatformAdmin.builder().createdDate(OLD).build(), PlatformAdmin::onCreate, PlatformAdmin::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(ProposalLink::new, () -> ProposalLink.builder().createdDate(OLD).build(), ProposalLink::onCreate, ProposalLink::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(SupplierInvoice::new, () -> SupplierInvoice.builder().createdDate(OLD).build(), SupplierInvoice::onCreate, SupplierInvoice::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(Tenant::new, () -> Tenant.builder().createdDate(OLD).build(), Tenant::onCreate, Tenant::getCreatedDate, OLD);
    }

    @Test
    void otherSingleTimestampsAreStampedOnlyWhenMissing() {
        stampsOnlyWhenEmpty(Feedback::new, () -> Feedback.builder().submittedAt(OLD).build(), Feedback::onCreate, Feedback::getSubmittedAt, OLD);
        stampsOnlyWhenEmpty(MemberDocument::new, () -> MemberDocument.builder().uploadedAt(OLD).build(), MemberDocument::onCreate, MemberDocument::getUploadedAt, OLD);
    }

    @Test
    void documentsDefaultBothTheirCreationStampAndTheirBusinessDate() {
        stampsOnlyWhenEmpty(Booking::new, () -> Booking.builder().createdDate(OLD).bookingDate(OLD_DATE).build(), Booking::onCreate, Booking::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(Booking::new, () -> Booking.builder().createdDate(OLD).bookingDate(OLD_DATE).build(), Booking::onCreate, Booking::getBookingDate, OLD_DATE);
        stampsOnlyWhenEmpty(ClientInvoice::new, () -> ClientInvoice.builder().createdDate(OLD).invoiceDate(OLD_DATE).build(), ClientInvoice::onCreate, ClientInvoice::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(ClientInvoice::new, () -> ClientInvoice.builder().createdDate(OLD).invoiceDate(OLD_DATE).build(), ClientInvoice::onCreate, ClientInvoice::getInvoiceDate, OLD_DATE);
        stampsOnlyWhenEmpty(Visa::new, () -> Visa.builder().createdDate(OLD).applicationDate(OLD_DATE).build(), Visa::onCreate, Visa::getCreatedDate, OLD);
        stampsOnlyWhenEmpty(Visa::new, () -> Visa.builder().createdDate(OLD).applicationDate(OLD_DATE).build(), Visa::onCreate, Visa::getApplicationDate, OLD_DATE);
        stampsOnlyWhenEmpty(ShiftHandover::new, () -> ShiftHandover.builder().createdAt(OLD).shiftEndedAt(OLD).build(), ShiftHandover::onCreate, ShiftHandover::getCreatedAt, OLD);
        stampsOnlyWhenEmpty(ShiftHandover::new, () -> ShiftHandover.builder().createdAt(OLD).shiftEndedAt(OLD).build(), ShiftHandover::onCreate, ShiftHandover::getShiftEndedAt, OLD);
        stampsOnlyWhenEmpty(PlatformSupplierCredential::new, () -> PlatformSupplierCredential.builder().createdAt(OLD_INSTANT).updatedAt(OLD_INSTANT).build(),
                PlatformSupplierCredential::onCreate, PlatformSupplierCredential::getCreatedAt, OLD_INSTANT);
        stampsOnlyWhenEmpty(PlatformSupplierCredential::new, () -> PlatformSupplierCredential.builder().createdAt(OLD_INSTANT).updatedAt(OLD_INSTANT).build(),
                PlatformSupplierCredential::onCreate, PlatformSupplierCredential::getUpdatedAt, OLD_INSTANT);
    }

    @Test
    void kycDocumentStartsPendingUnlessGivenAStatus() {
        stampsOnlyWhenEmpty(KycDocument::new, () -> KycDocument.builder().createdAt(OLD_INSTANT).status(KycDocumentStatus.VERIFIED).build(),
                KycDocument::onCreate, KycDocument::getCreatedAt, OLD_INSTANT);

        KycDocument fresh = new KycDocument();
        fresh.onCreate();
        assertThat(fresh.getStatus()).isEqualTo(KycDocumentStatus.PENDING);

        KycDocument verified = KycDocument.builder().status(KycDocumentStatus.VERIFIED).build();
        verified.onCreate();
        assertThat(verified.getStatus()).isEqualTo(KycDocumentStatus.VERIFIED);
    }

    @Test
    void agencyApplicationKeepsItsCreationStampAndRefreshesUpdatedAtOnEveryWrite() {
        AgencyApplication fresh = new AgencyApplication();
        fresh.onCreate();
        assertThat(fresh.getCreatedAt()).isNotNull().isEqualTo(fresh.getUpdatedAt());

        AgencyApplication old = AgencyApplication.builder().createdAt(OLD_INSTANT).updatedAt(OLD_INSTANT).build();
        old.onCreate();
        assertThat(old.getCreatedAt()).isEqualTo(OLD_INSTANT);
        assertThat(old.getUpdatedAt()).isAfter(OLD_INSTANT);

        AgencyApplication updated = AgencyApplication.builder().createdAt(OLD_INSTANT).updatedAt(OLD_INSTANT).build();
        updated.onUpdate();
        assertThat(updated.getCreatedAt()).isEqualTo(OLD_INSTANT);
        assertThat(updated.getUpdatedAt()).isAfter(OLD_INSTANT);
    }

    @Test
    void tenantStatusKeepsTheLegacyActiveFlagInStep() {
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.SUSPENDED);
        assertThat(tenant.getIsActive()).isTrue();
        tenant.setStatus(TenantStatus.DEACTIVATED);
        assertThat(tenant.getIsActive()).isFalse();
        tenant.setStatus(TenantStatus.ACTIVE);
        assertThat(tenant.getIsActive()).isTrue();
    }

    @Test
    void supplierInvoicePayableIsTheBillLessCommissionPlusTds() {
        SupplierInvoice bill = SupplierInvoice.builder().grandTotal(new BigDecimal("1000.00")).grandTotalInr(new BigDecimal("83000.00"))
                .commission(new BigDecimal("50.00")).supplierTds(new BigDecimal("2.50")).fxRateToInr(new BigDecimal("83")).build();
        assertThat(bill.payable()).isEqualByComparingTo("952.50");
        assertThat(bill.payableInr()).isEqualByComparingTo("79057.50");
    }

    @Test
    void supplierInvoiceTreatsMissingCommissionTdsAndRateAsZeroZeroAndOne() {
        SupplierInvoice bill = SupplierInvoice.builder().grandTotal(new BigDecimal("1000.00")).grandTotalInr(new BigDecimal("1000.00"))
                .commission(null).supplierTds(null).fxRateToInr(null).build();
        assertThat(bill.payable()).isEqualByComparingTo("1000.00");
        assertThat(bill.payableInr()).isEqualByComparingTo("1000.00");
        assertThat(bill.inr(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
    }
}
