package com.voyra.crm.service.payments;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StubPaymentGatewayTest {

    @Test
    void simulationFollowsTheSwitch() {
        assertThat(new StubPaymentGateway(true).simulateEnabled()).isTrue();
        assertThat(new StubPaymentGateway(false).simulateEnabled()).isFalse();
    }
}
