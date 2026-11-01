package com.voyra.crm.util;

import java.util.Optional;

/** Offer and room ids reach the browser as {@code <supplierId>:<supplier's own id>}, so one id names its supplier. */
public final class SupplierIds {

    public record Scoped(String supplierId, String id) { }

    private SupplierIds() {
    }

    public static String wrap(String supplierId, String id) {
        return id == null ? null : supplierId + ":" + id;
    }

    /** Splits at the first colon only (a supplier's own id may contain colons); no namespace or a blank side is empty. */
    public static Optional<Scoped> parse(String scoped) {
        int colon = scoped == null ? -1 : scoped.indexOf(':');
        if (colon <= 0 || colon == scoped.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new Scoped(scoped.substring(0, colon), scoped.substring(colon + 1)));
    }
}
