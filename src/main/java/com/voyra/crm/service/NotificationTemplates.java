package com.voyra.crm.service;

import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One email per notification kind: a subject, inline-styled HTML (UI_BLUEPRINT teal #4a8285, no images, no tracking) and a
 * plain-text alternative built from the same lines. Every value is HTML-escaped. Pure functions, no I/O.
 */
public final class NotificationTemplates {

    public record Email(String subject, String html, String text) {
    }

    private static final String TEAL = "#4a8285";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(INDIA);

    private NotificationTemplates() {
    }

    public static Email lowBalance(String agencyName, BigDecimal available, String walletUrl) {
        return email("Your Voyra wallet is running low", "Wallet running low",
                List.of("Hello " + agencyName + ",",
                        "Your Voyra wallet has " + inr(available) + " available. Bookings need enough balance to hold the fare, "
                                + "so top up soon to keep booking without interruption."),
                "Top up the wallet", walletUrl);
    }

    public static Email platformAlert(String subject, String body) {
        return email("[Voyra alert] " + oneLine(subject), oneLine(subject), List.of(body), null, null);
    }

    public static Email subscriptionExpiring(String agencyName, String kind, Instant endsAt, int daysLeft, String url) {
        String what = switch (kind) {
            case "TRIAL" -> "free trial";
            case "WAIVER" -> "complimentary plan";
            default -> "subscription period";
        };
        String days = daysLeft + (daysLeft == 1 ? " day" : " days");
        return email("Your Voyra " + what + " ends in " + days, "Your " + what + " ends soon",
                List.of("Hello " + agencyName + ",",
                        "Your Voyra " + what + " ends on " + date(endsAt) + " (" + days + " left).",
                        "Pay the subscription before then to keep full access. Your data stays safe either way."),
                "View subscription", url);
    }

    public static Email subscriptionInvoice(String agencyName, String number, BigDecimal total, Instant dueAt, String url) {
        return email("Voyra invoice " + number + " - " + inr(total), "New subscription invoice",
                List.of("Hello " + agencyName + ",",
                        "Invoice " + number + " for " + inr(total) + " (GST included) has been raised for your Voyra subscription.",
                        "It is due on " + date(dueAt) + ". You can pay it and download the PDF from your subscription page."),
                "View invoice", url);
    }

    public static Email topupApproved(String agencyName, BigDecimal amount, String url) {
        return email("Wallet top-up of " + inr(amount) + " credited", "Top-up credited",
                List.of("Hello " + agencyName + ",", inr(amount) + " has been credited to your Voyra wallet."),
                "Open the wallet", url);
    }

    public static Email topupRejected(String agencyName, BigDecimal amount, String reason, String url) {
        List<String> lines = new ArrayList<>(List.of("Hello " + agencyName + ",",
                "Your wallet top-up request for " + inr(amount) + " was not approved, and nothing was credited."));
        if (reason != null && !reason.isBlank()) {
            lines.add("Reason: " + reason);
        }
        lines.add("If you did transfer the money, raise a new request with the correct UTR and proof.");
        return email("Wallet top-up of " + inr(amount) + " not approved", "Top-up not approved", lines, "Open the wallet", url);
    }

    public static Email applicationMoreInfo(String ownerName, String agencyName, String note, String resumeUrl) {
        List<String> lines = new ArrayList<>(List.of("Hello " + ownerName + ",",
                "We need a little more information to finish reviewing the application for " + agencyName + "."));
        if (note != null && !note.isBlank()) {
            lines.add("What we need: " + note);
        }
        lines.add("Use the button below to reopen your application. The link works for 7 days and only for you, "
                + "so please do not forward this email.");
        return email("More information needed for your Voyra application", "More information needed", lines,
                "Update the application", resumeUrl);
    }

    public static Email applicationRejected(String ownerName, String agencyName, String reason) {
        List<String> lines = new ArrayList<>(List.of("Hello " + ownerName + ",",
                "Thank you for applying to Voyra. We are unable to approve the application for " + agencyName + " at this time."));
        if (reason != null && !reason.isBlank()) {
            lines.add("Reason: " + reason);
        }
        lines.add("You are welcome to apply again once this is resolved.");
        return email("Your Voyra application", "Application not approved", lines, null, null);
    }

    public static Email applicationApproved(String ownerName, String agencyName, String loginEmail, String loginUrl) {
        return email("Welcome to Voyra - " + oneLine(agencyName) + " is approved", "Your agency is approved",
                List.of("Hello " + ownerName + ",",
                        agencyName + " is now live on Voyra.",
                        "Sign in with " + loginEmail + ". The Voyra team shares your first password separately; "
                                + "change it after your first sign-in."),
                "Sign in", loginUrl);
    }

    public static Email bookingTicketed(String product, String bookingId, String pnr, BigDecimal amount, String url) {
        boolean hotel = "hotel".equals(product);
        String ref = hotel ? "Confirmation " : "PNR ";
        return email("Your " + product + " booking is confirmed - " + ref + pnr, hotel ? "Hotel booking confirmed" : "Flight ticketed",
                List.of("Booking " + bookingId + " is confirmed. " + ref + pnr + ".",
                        inr(amount) + " has been debited from your Voyra wallet."),
                "Open the booking", url);
    }

    public static Email bookingFailed(String product, String bookingId, String url) {
        return email("Your " + product + " booking could not be completed", "Booking not completed",
                List.of("The supplier could not complete booking " + bookingId + ".",
                        "Nothing was charged: the amount held in your Voyra wallet has been released. You can search again and rebook."),
                "Open the booking", url);
    }

    public static Email bookingCancelled(String product, String bookingId, String pnr, BigDecimal refund, boolean refundOffline, String url) {
        String ref = pnr == null ? "" : " (" + ("hotel".equals(product) ? "confirmation " : "PNR ") + pnr + ")";
        String money;
        if (refund == null) {
            money = refundOffline ? "Voyra will confirm the refund with the supplier and pay it to your bank account."
                    : "Voyra will confirm the refund with the supplier and credit your wallet once it is known.";
        } else if (refund.signum() <= 0) {
            money = "No refund applies under the fare's cancellation rules.";
        } else {
            // offline: the wallet was closed and nothing was posted to it
            money = refundOffline ? "Voyra will refund " + inr(refund) + " to your bank account."
                    : inr(refund) + " has been refunded to your Voyra wallet.";
        }
        return email("Your " + product + " booking" + ref + " is cancelled", "Booking cancelled",
                List.of("Booking " + bookingId + ref + " has been cancelled.", money), "Open the booking", url);
    }

    // ---- layout

    private static Email email(String subject, String heading, List<String> lines, String buttonLabel, String buttonUrl) {
        StringBuilder html = new StringBuilder(1500)
                .append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>").append(esc(heading)).append("</title></head>")
                .append("<body style=\"margin:0;padding:24px;background:#f4f7f7;font-family:Arial,Helvetica,sans-serif;color:#1f2937;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;margin:0 auto;")
                .append("background:#ffffff;border:1px solid #e5e7eb;border-radius:8px;\">")
                .append("<tr><td style=\"background:").append(TEAL).append(";color:#ffffff;padding:16px 24px;border-radius:8px 8px 0 0;")
                .append("font-size:18px;font-weight:bold;letter-spacing:2px;\">VOYRA</td></tr>")
                .append("<tr><td style=\"padding:24px;\"><h1 style=\"margin:0 0 16px;font-size:20px;color:").append(TEAL).append(";\">")
                .append(esc(heading)).append("</h1>");
        StringBuilder text = new StringBuilder(600).append(heading).append("\n\n");
        for (String line : lines) {
            html.append("<p style=\"margin:0 0 12px;font-size:14px;line-height:1.5;\">").append(esc(line)).append("</p>");
            text.append(line).append("\n\n");
        }
        if (buttonUrl != null) {
            html.append("<p style=\"margin:20px 0 8px;\"><a href=\"").append(esc(buttonUrl)).append("\" style=\"display:inline-block;")
                    .append("background:").append(TEAL).append(";color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:6px;")
                    .append("font-size:14px;font-weight:bold;\">").append(esc(buttonLabel)).append("</a></p>")
                    .append("<p style=\"margin:0;font-size:12px;color:#6b7280;word-break:break-all;\">").append(esc(buttonUrl)).append("</p>");
            text.append(buttonLabel).append(": ").append(buttonUrl).append("\n\n");
        }
        html.append("</td></tr><tr><td style=\"padding:16px 24px;border-top:1px solid #e5e7eb;font-size:12px;color:#6b7280;\">")
                .append("This is an automatic message from Voyra.</td></tr></table></body></html>");
        text.append("This is an automatic message from Voyra.\n");
        return new Email(oneLine(subject), html.toString(), text.toString());
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s, "UTF-8");
    }

    /** Header injection guard: a subject is always a single line. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String date(Instant instant) {
        return instant == null ? "-" : DATE.format(instant);
    }

    /** Indian grouping: ₹12,34,567.50 (DecimalFormat cannot do the 2-digit groups). */
    static String inr(BigDecimal amount) {
        if (amount == null) {
            return "₹0.00";
        }
        String plain = amount.setScale(2, RoundingMode.HALF_UP).abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            grouped.append(whole.charAt(i));
            int rest = whole.length() - i - 1;
            if (rest == 3 || (rest > 3 && (rest - 3) % 2 == 0)) {
                grouped.append(',');
            }
        }
        return (amount.signum() < 0 ? "-₹" : "₹") + grouped + plain.substring(dot);
    }
}
