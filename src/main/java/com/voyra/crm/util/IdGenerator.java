package com.voyra.crm.util;

import java.security.SecureRandom;
import java.util.UUID;

/** Collision-checked identifiers: UUIDs for entity primary keys, base-36 for the share-link token. */
public final class IdGenerator {

    private static final String BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    /** Standard UUID (36 chars, lowercase, dashed). Used for entity primary keys. */
    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    /** High-entropy token for the public proposal share-link (not a primary key). */
    public static String generateToken32() {
        return generateAlphanumeric(32);
    }

    public static String generateAlphanumeric(int length) {
        if (length <= 0 || length > 32) {
            throw new IllegalArgumentException("Length must be between 1 and 32");
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = BASE36_CHARS.charAt(RANDOM.nextInt(BASE36_CHARS.length()));
        }
        return new String(chars);
    }
}
