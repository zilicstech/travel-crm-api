package com.voyra.crm.util;

import com.voyra.crm.enums.InvoiceLifecycle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceLifecyclePolicyTest {

    @Test
    void onlyDraftIsEditable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertEditable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertEditable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertEditable(InvoiceLifecycle.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyDraftIsDeletable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertDeletable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertDeletable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyDraftIsIssuable() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertIssuable(InvoiceLifecycle.DRAFT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertIssuable(InvoiceLifecycle.ISSUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyIssuedIsCancellableAtThisStage() {
        assertThatCode(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.ISSUED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.DRAFT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InvoiceLifecyclePolicy.assertCancellable(InvoiceLifecycle.CANCELLED))
                .isInstanceOf(IllegalStateException.class);
    }
}
