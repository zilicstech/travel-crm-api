package com.voyra.crm.util;

import com.voyra.crm.enums.SupplierInvoiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static com.voyra.crm.enums.SupplierInvoiceStatus.APPROVED;
import static com.voyra.crm.enums.SupplierInvoiceStatus.DRAFT;
import static com.voyra.crm.enums.SupplierInvoiceStatus.PAID;
import static com.voyra.crm.enums.SupplierInvoiceStatus.PARTIALLY_PAID;
import static com.voyra.crm.enums.SupplierInvoiceStatus.PENDING_APPROVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierInvoiceLifecyclePolicyTest {

    @Test
    void onlyADraftIsEditableOrDeletable() {
        assertThatCode(() -> SupplierInvoiceLifecyclePolicy.assertEditable(DRAFT)).doesNotThrowAnyException();
        assertThatCode(() -> SupplierInvoiceLifecyclePolicy.assertDeletable(DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SupplierInvoiceLifecyclePolicy.assertEditable(APPROVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bill is APPROVED and can no longer be edited - correct it with a supplier credit note instead");
        assertThatThrownBy(() -> SupplierInvoiceLifecyclePolicy.assertDeletable(PAID))
                .isInstanceOf(IllegalStateException.class).hasMessage("Only a draft bill can be deleted");
    }

    @ParameterizedTest
    @EnumSource(SupplierInvoiceStatus.class)
    void approvableOnlyFromDraftOrPendingApproval(SupplierInvoiceStatus status) {
        if (status == DRAFT || status == PENDING_APPROVAL) {
            assertThatCode(() -> SupplierInvoiceLifecyclePolicy.assertApprovable(status)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> SupplierInvoiceLifecyclePolicy.assertApprovable(status))
                    .hasMessage("Only a draft or pending-approval bill can be approved");
        }
    }

    @ParameterizedTest
    @EnumSource(SupplierInvoiceStatus.class)
    void cancellableOnlyBeforeAnyPayment(SupplierInvoiceStatus status) {
        if (status == DRAFT || status == PENDING_APPROVAL || status == APPROVED) {
            assertThatCode(() -> SupplierInvoiceLifecyclePolicy.assertCancellable(status)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> SupplierInvoiceLifecyclePolicy.assertCancellable(status))
                    .hasMessage("Only a draft, pending-approval or approved bill can be cancelled at this stage");
        }
    }

    @ParameterizedTest
    @EnumSource(SupplierInvoiceStatus.class)
    void payableOnlyWhenApprovedOrPartlyPaid(SupplierInvoiceStatus status) {
        if (status == APPROVED || status == PARTIALLY_PAID) {
            assertThatCode(() -> SupplierInvoiceLifecyclePolicy.assertPayable(status)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> SupplierInvoiceLifecyclePolicy.assertPayable(status))
                    .hasMessage("Payments can only be recorded against an approved or partially paid bill");
        }
    }

    @Test
    void theBalanceDecidesPaidPartlyPaidOrApproved() {
        BigDecimal total = new BigDecimal("100");
        assertThat(SupplierInvoiceLifecyclePolicy.deriveFromBalance(total, BigDecimal.ZERO)).isEqualTo(PAID);
        assertThat(SupplierInvoiceLifecyclePolicy.deriveFromBalance(total, new BigDecimal("-1"))).isEqualTo(PAID);
        assertThat(SupplierInvoiceLifecyclePolicy.deriveFromBalance(total, new BigDecimal("40"))).isEqualTo(PARTIALLY_PAID);
        assertThat(SupplierInvoiceLifecyclePolicy.deriveFromBalance(total, total)).isEqualTo(APPROVED);
    }
}
