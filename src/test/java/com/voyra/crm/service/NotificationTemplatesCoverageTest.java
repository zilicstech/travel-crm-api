package com.voyra.crm.service;

import com.voyra.crm.service.NotificationTemplates.Email;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplatesCoverageTest {

    @Test
    void eachExpiryKindNamesWhatEndsAndOneDayIsSingular() {
        Instant end = Instant.parse("2026-11-30T10:00:00Z");
        assertThat(NotificationTemplates.subscriptionExpiring("Sun Tours", "TRIAL", end, 1, "u").subject())
                .isEqualTo("Your Voyra free trial ends in 1 day");
        assertThat(NotificationTemplates.subscriptionExpiring("Sun Tours", "WAIVER", end, 7, "u").subject())
                .isEqualTo("Your Voyra complimentary plan ends in 7 days");
        Email period = NotificationTemplates.subscriptionExpiring("Sun Tours", "PERIOD", null, 14, "u");
        assertThat(period.subject()).isEqualTo("Your Voyra subscription period ends in 14 days");
        assertThat(period.text()).contains("ends on - (14 days left)");
    }

    @Test
    void optionalReasonsAndNotesAreLeftOutWhenBlank() {
        for (String blank : new String[]{null, " "}) {
            assertThat(NotificationTemplates.topupRejected("Sun Tours", BigDecimal.TEN, blank, "u").text()).doesNotContain("Reason:");
            assertThat(NotificationTemplates.applicationMoreInfo("Dana", "Sun Tours", blank, "u").text()).doesNotContain("What we need:");
            assertThat(NotificationTemplates.applicationRejected("Dana", "Sun Tours", blank).text()).doesNotContain("Reason:");
        }
        assertThat(NotificationTemplates.applicationRejected("Dana", "Sun Tours", "GSTIN invalid").text()).contains("Reason: GSTIN invalid");
        assertThat(NotificationTemplates.topupRejected("Sun Tours", BigDecimal.TEN, "UTR not found", "u").text()).contains("Reason: UTR not found");
    }

    @Test
    void aHotelIsConfirmedByItsConfirmationNumber() {
        Email e = NotificationTemplates.bookingTicketed("hotel", "PB1", "HC-9", new BigDecimal("8000"), "u");
        assertThat(e.subject()).isEqualTo("Your hotel booking is confirmed - Confirmation HC-9");
        assertThat(e.html()).contains("Hotel booking confirmed");
        assertThat(e.text()).contains("Booking PB1 is confirmed. Confirmation HC-9.");
    }

    @Test
    void aCancellationNamesItsReferenceAndWhatComesBack() {
        assertThat(NotificationTemplates.bookingCancelled("flight", "PB1", null, BigDecimal.ZERO, false, "u").subject())
                .isEqualTo("Your flight booking is cancelled");
        Email none = NotificationTemplates.bookingCancelled("hotel", "PB1", "HC-9", BigDecimal.ZERO, false, "u");
        assertThat(none.subject()).isEqualTo("Your hotel booking (confirmation HC-9) is cancelled");
        assertThat(none.text()).contains("No refund applies under the fare's cancellation rules.");
        assertThat(NotificationTemplates.bookingCancelled("flight", "PB1", "PNR1", new BigDecimal("1500"), false, "u").text())
                .contains("₹1,500.00 has been refunded to your Voyra wallet.");
        assertThat(NotificationTemplates.bookingCancelled("flight", "PB1", "PNR1", new BigDecimal("1500"), true, "u").text())
                .contains("Voyra will refund ₹1,500.00 to your bank account.");
    }

    @Test
    void aMissingSubjectIsBlankAndAMultiLineNameIsOneLine() {
        Email alert = NotificationTemplates.platformAlert(null, "body");
        assertThat(alert.subject()).isEqualTo("[Voyra alert]");
        Email welcome = NotificationTemplates.applicationApproved("Dana", "Sun\nTours", "owner@example.test", "https://app.example.test");
        assertThat(welcome.subject()).isEqualTo("Welcome to Voyra - Sun Tours is approved");
        assertThat(welcome.text()).contains("Sign in: https://app.example.test");
    }

    @Test
    void moneyIsGroupedTheIndianWayIncludingNegativesAndNothing() {
        assertThat(NotificationTemplates.inr(null)).isEqualTo("₹0.00");
        assertThat(NotificationTemplates.inr(new BigDecimal("-1234567.5"))).isEqualTo("-₹12,34,567.50");
        assertThat(NotificationTemplates.inr(new BigDecimal("999"))).isEqualTo("₹999.00");
    }
}
