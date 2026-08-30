package com.voyra.crm.util;

import com.voyra.crm.entity.Visa;
import com.voyra.crm.enums.VisaStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The priority ladder this calculator exists to enforce: REJECTED > APPROVED > SUBMITTED > APPOINTMENT_SCHEDULED > DOCUMENTS_PENDING. */
class VisaStatusCalculatorTest {

    private Visa.VisaBuilder baseVisa() {
        return Visa.builder().id("V1").clientId("C1").clientName("Jane").agentId("A1").agentName("Liam")
                .country("UAE").visaType("Tourist");
    }

    @Test
    void noBooleansSetYieldsDocumentsPending() {
        Visa visa = baseVisa().build();
        assertThat(VisaStatusCalculator.calculate(visa)).isEqualTo(VisaStatus.DOCUMENTS_PENDING);
    }

    @Test
    void appointmentDateAloneYieldsAppointmentScheduled() {
        Visa visa = baseVisa().appointmentDate(LocalDate.now().plusDays(3)).build();
        assertThat(VisaStatusCalculator.calculate(visa)).isEqualTo(VisaStatus.APPOINTMENT_SCHEDULED);
    }

    @Test
    void submittedToEmbassyTakesPrecedenceOverAppointmentDate() {
        Visa visa = baseVisa()
                .appointmentDate(LocalDate.now().plusDays(3))
                .submittedToEmbassy(true)
                .build();
        assertThat(VisaStatusCalculator.calculate(visa)).isEqualTo(VisaStatus.SUBMITTED);
    }

    @Test
    void approvedTakesPrecedenceOverSubmitted() {
        Visa visa = baseVisa().submittedToEmbassy(true).approved(true).build();
        assertThat(VisaStatusCalculator.calculate(visa)).isEqualTo(VisaStatus.APPROVED);
    }

    @Test
    void rejectedTakesPrecedenceOverEverythingIncludingApproved() {
        // The exact overlap case the ladder exists to resolve: both booleans true at once.
        Visa visa = baseVisa().approved(true).rejected(true).build();
        assertThat(VisaStatusCalculator.calculate(visa)).isEqualTo(VisaStatus.REJECTED);
    }

    @Test
    void rejectedTakesPrecedenceOverSubmittedAndAppointment() {
        Visa visa = baseVisa()
                .appointmentDate(LocalDate.now())
                .submittedToEmbassy(true)
                .rejected(true)
                .build();
        assertThat(VisaStatusCalculator.calculate(visa)).isEqualTo(VisaStatus.REJECTED);
    }
}
