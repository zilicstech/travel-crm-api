package com.voyra.crm.util;

import java.util.function.Predicate;

/**
 * Blueprint §8.5: entity primary keys are UUID strings, so every insert must
 * retry against an existence check and fail loudly rather than loop forever. Collision
 * probability with UUIDs is effectively zero, but the retry loop is kept for safety.
 * Centralised here so no insert path can silently skip the check.
 */
public final class UniqueIdResolver {

    private static final int MAX_ATTEMPTS = 10;

    private UniqueIdResolver() {
    }

    /** @param existsById typically {@code repository::existsById} */
    public static String resolve(Predicate<String> existsById) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String id = IdGenerator.generateId();
            if (!existsById.test(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Unable to generate a unique id after " + MAX_ATTEMPTS + " attempts");
    }
}
