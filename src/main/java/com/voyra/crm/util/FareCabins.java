package com.voyra.crm.util;

import com.voyra.crm.enums.FlightCabin;

import java.util.Locale;

/**
 * The cabin a fare belongs to, so the fare type filter shows a few cabins instead of every airline's fare name (owner ruling
 * 2026-11-20): the supplier's cabin when it sends one, else words in the fare's name, else the searched cabin.
 */
public final class FareCabins {

    private FareCabins() {
    }

    public static FlightCabin of(String supplierCabin, String fareName, FlightCabin searched) {
        FlightCabin sent = parse(supplierCabin);
        if (sent != null) {
            return sent;
        }
        String name = fareName == null ? "" : fareName.toLowerCase(Locale.ROOT);
        // premium before economy ("Premium Economy"), and "eco" also catches "Eco Flex" and "Economy Saver"
        if (name.contains("premium")) {
            return FlightCabin.PREMIUM_ECONOMY;
        }
        if (name.contains("business")) {
            return FlightCabin.BUSINESS;
        }
        if (name.contains("first")) {
            return FlightCabin.FIRST;
        }
        if (name.contains("eco")) {
            return FlightCabin.ECONOMY;
        }
        return searched == null ? FlightCabin.ECONOMY : searched;
    }

    /** "ECONOMY", "premium economy", "Premium_Economy" ...; null for anything else. */
    private static FlightCabin parse(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (FlightCabin c : FlightCabin.values()) {
            if (c.name().equals(key)) {
                return c;
            }
        }
        return null;
    }
}
