package com.voyra.crm.service;

import com.voyra.crm.service.NotificationTemplates.Email;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplatesTest {

    private static final String URL = "https://app.voyra.test";
    private static final Instant WHEN = Instant.parse("2026-11-09T20:00:00Z"); // 10 Nov 2026 in India

    private static List<Email> everyKind() {
        return List.of(
                NotificationTemplates.lowBalance("Sky Tours", new BigDecimal("1234.5"), URL + "/agency/wallet"),
                NotificationTemplates.platformAlert("Wallet receivable opened", "Agency T1 is short by 10"),
                NotificationTemplates.subscriptionExpiring("Sky Tours", "TRIAL", WHEN, 7, URL + "/agency/subscription"),
                NotificationTemplates.subscriptionInvoice("Sky Tours", "VOY-SUB-2026-0001", new BigDecimal("5898.82"), WHEN,
                        URL + "/agency/subscription"),
                NotificationTemplates.topupApproved("Sky Tours", new BigDecimal("50000"), URL + "/agency/wallet"),
                NotificationTemplates.topupRejected("Sky Tours", new BigDecimal("50000"), "UTR not found", URL + "/agency/wallet"),
                NotificationTemplates.applicationMoreInfo("Asha", "Sky Tours", "GST certificate", URL + "/apply?application=a1&token=t1"),
                NotificationTemplates.applicationRejected("Asha", "Sky Tours", "PAN unreadable"),
                NotificationTemplates.applicationApproved("Asha", "Sky Tours", "asha@sky.test", URL + "/login"),
                NotificationTemplates.bookingTicketed("flight", "PB1", "PNR123", new BigDecimal("4500"), URL + "/agency/platform-bookings/PB1"),
                NotificationTemplates.bookingFailed("hotel", "PB2", URL + "/agency/platform-bookings/PB2"),
                NotificationTemplates.bookingCancelled("flight", "PB1", "PNR123", new BigDecimal("4000"), false, URL + "/agency/platform-bookings/PB1"));
    }

    @Test
    void everyKindRendersSubjectHtmlAndAPlainTextAlternative() {
        for (Email e : everyKind()) {
            assertThat(e.subject()).isNotBlank().doesNotContain("\n");
            assertThat(e.html()).startsWith("<!DOCTYPE html>").contains("#4a8285").contains("Voyra");
            assertThat(e.text()).isNotBlank().doesNotContain("<");
        }
    }

    @Test
    void noTrackingPixelsImagesOrScripts() {
        for (Email e : everyKind()) {
            assertThat(e.html().toLowerCase()).doesNotContain("<img").doesNotContain("<script").doesNotContain("background-image");
        }
    }

    @Test
    void valuesAreHtmlEscaped() {
        Email e = NotificationTemplates.topupRejected("<b>Evil</b> & Co", new BigDecimal("10"), "<script>x()</script>", URL);

        assertThat(e.html()).doesNotContain("<script>").doesNotContain("<b>Evil")
                .contains("&lt;script&gt;").contains("&lt;b&gt;Evil&lt;/b&gt; &amp; Co");
        assertThat(e.text()).contains("<b>Evil</b> & Co").contains("<script>x()</script>");
    }

    @Test
    void moneyAndDatesAreFormattedForIndia() {
        Email low = NotificationTemplates.lowBalance("Sky Tours", new BigDecimal("1234567.5"), URL);
        assertThat(low.text()).contains("₹12,34,567.50");

        Email exp = NotificationTemplates.subscriptionExpiring("Sky Tours", "TRIAL", WHEN, 1, URL);
        assertThat(exp.text()).contains("10 Nov 2026").contains("1 day");
        assertThat(exp.subject()).containsIgnoringCase("trial");
    }

    @Test
    void theMoreInfoEmailCarriesTheResumeLink() {
        Email e = NotificationTemplates.applicationMoreInfo("Asha", "Sky Tours", "Upload the GST certificate",
                URL + "/apply?application=a1&token=t1");

        assertThat(e.text()).contains(URL + "/apply?application=a1&token=t1").contains("Upload the GST certificate").contains("7 days");
        assertThat(e.html()).contains("href=\"" + URL + "/apply?application=a1&amp;token=t1\"");
    }

    @Test
    void theApprovalNeverMentionsAPassword() {
        Email e = NotificationTemplates.applicationApproved("Asha", "Sky Tours", "asha@sky.test", URL + "/login");

        assertThat(e.text()).contains("asha@sky.test").contains(URL + "/login");
        assertThat(e.text().toLowerCase()).doesNotContain("password:");
    }

    @Test
    void aCancellationWithoutAKnownRefundSaysVoyraWillConfirmIt() {
        Email unknown = NotificationTemplates.bookingCancelled("flight", "PB1", "PNR123", null, false, URL);
        Email known = NotificationTemplates.bookingCancelled("flight", "PB1", "PNR123", new BigDecimal("4000"), false, URL);

        assertThat(unknown.text()).contains("PNR123").contains("confirm");
        assertThat(known.text()).contains("₹4,000.00");
    }

    /** Closed wallet: nothing was posted, so the email must not claim a wallet refund. */
    @Test
    void anOfflineRefundSaysBankAccountNotWallet() {
        Email offline = NotificationTemplates.bookingCancelled("flight", "PB1", "PNR123", new BigDecimal("4000"), true, URL);
        Email offlineUnknown = NotificationTemplates.bookingCancelled("flight", "PB1", "PNR123", null, true, URL);

        assertThat(offline.text()).contains("Voyra will refund ₹4,000.00 to your bank account").doesNotContain("wallet");
        assertThat(offlineUnknown.text()).contains("confirm").doesNotContain("credit your wallet");
    }

    @Test
    void bookingEmailsNameTheBookingAndAmount() {
        Email t = NotificationTemplates.bookingTicketed("flight", "PB1", "PNR123", new BigDecimal("4500"), URL + "/x");

        assertThat(t.subject()).contains("PNR123");
        assertThat(t.text()).contains("PB1").contains("₹4,500.00").contains(URL + "/x");
    }

    @Test
    void platformAlertKeepsTheCallersSubjectAndBody() {
        Email e = NotificationTemplates.platformAlert("Late ticket on booking PB1", "Booking PB1 ticketed after release");

        assertThat(e.subject()).contains("Late ticket on booking PB1");
        assertThat(e.text()).contains("Booking PB1 ticketed after release");
    }
}
