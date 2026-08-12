package com.voyra.crm.util;

import java.security.SecureRandom;

/** Short, human-shareable, collision-checked, uppercase base-36 identifiers. */
public final class IdGenerator {

    private static final String BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    /** 6 chars ~ 2.1B combinations. Used for entity primary keys. */
    public static String generate6() {
        return generateAlphanumeric(6);
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
