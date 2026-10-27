package com.voyra.crm.util;

import com.voyra.crm.dto.VoucherLeg;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The travellers and flights on an air ticket voucher. The invoice prints both, so a ticket isn't
 * saved without them; the voucher's flat flight details (what the card, completion job and invoice
 * description read) are worked out from the legs.
 */
public final class VoucherTrip {

    private static final int MAX_LEGS = 8;
    private static final int MAX_LENGTH = 60;

    private VoucherTrip() {
    }

    /** Trimmed legs, airport codes upper-cased; blank rows dropped. Refuses a leg without from, to and date. */
    public static List<VoucherLeg> cleanLegs(List<VoucherLeg> raw) {
        List<VoucherLeg> out = new ArrayList<>();
        for (VoucherLeg l : raw != null ? raw : List.<VoucherLeg>of()) {
            if (l == null) continue;
            VoucherLeg c = VoucherLeg.builder()
                    .from(code(l.getFrom())).to(code(l.getTo())).airline(text(l.getAirline()))
                    .flightNumber(text(l.getFlightNumber())).cabin(text(l.getCabin())).travelDate(l.getTravelDate())
                    .departTime(text(l.getDepartTime())).arriveTime(text(l.getArriveTime())).build();
            boolean blank = c.getFrom() == null && c.getTo() == null && c.getTravelDate() == null && c.getFlightNumber() == null;
            if (blank) continue;
            if (c.getFrom() == null || c.getTo() == null || c.getTravelDate() == null) {
                throw new IllegalArgumentException("Flight " + (out.size() + 1) + " needs from, to and the travel date");
            }
            if (!out.isEmpty() && c.getTravelDate().isBefore(out.get(out.size() - 1).getTravelDate())) {
                throw new IllegalArgumentException("Flight " + (out.size() + 1) + " can't be before the one it connects from");
            }
            out.add(c);
        }
        if (out.size() > MAX_LEGS) {
            throw new IllegalArgumentException("A ticket holds at most " + MAX_LEGS + " flights");
        }
        return out;
    }

    /** A ticket names at least one traveller and one flight. */
    public static void check(int travellers, List<VoucherLeg> legs) {
        if (travellers == 0) {
            throw new IllegalArgumentException("Add the travellers on this ticket");
        }
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("Add the flight - from, to and the travel date");
        }
    }

    /** The voucher's flat flight details from its legs: where and when it starts, where and when it ends. */
    public static Map<String, String> details(List<VoucherLeg> legs, Map<String, String> details) {
        Map<String, String> out = new LinkedHashMap<>(details != null ? details : Map.of());
        if (legs.isEmpty()) return out.isEmpty() ? null : out;
        VoucherLeg first = legs.get(0);
        VoucherLeg last = legs.get(legs.size() - 1);
        List.of("airline", "flightNumber", "flightFrom", "flightTo", "journeyDate", "returnDate",
                "flightDepartTime", "flightArriveTime", "flightArriveDate", "flightCabin").forEach(out::remove);
        put(out, "airline", first.getAirline());
        put(out, "flightNumber", first.getFlightNumber());
        put(out, "flightFrom", first.getFrom());
        // Out and back (DEL-BOM-DEL) reads DEL to BOM, returning on the last flight's date - not DEL to DEL.
        boolean outAndBack = legs.size() > 1 && Objects.equals(first.getFrom(), last.getTo());
        put(out, "flightTo", outAndBack ? first.getTo() : last.getTo());
        if (outAndBack) put(out, "returnDate", last.getTravelDate().toString());
        put(out, "journeyDate", first.getTravelDate().toString());
        put(out, "flightDepartTime", first.getDepartTime());
        put(out, "flightArriveTime", last.getArriveTime());
        put(out, "flightCabin", first.getCabin());
        if (!Objects.equals(last.getTravelDate(), first.getTravelDate())) {
            put(out, "flightArriveDate", last.getTravelDate().toString());
        }
        return out;
    }

    /** The first flight's date, for working out a traveller's pax type. */
    public static LocalDate firstDate(List<VoucherLeg> legs) {
        return legs == null || legs.isEmpty() ? null : legs.get(0).getTravelDate();
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    private static String code(String s) {
        String t = text(s);
        return t == null ? null : t.toUpperCase();
    }

    private static String text(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > MAX_LENGTH ? t.substring(0, MAX_LENGTH) : t;
    }
}
