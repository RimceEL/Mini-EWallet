package com.backend.backend.global;

import java.util.Locale;

public final class DataNormalizer {

    private DataNormalizer() {
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String result = email.strip().toLowerCase(Locale.ROOT);
        return result.isEmpty() ? null : result;
    }

    public static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String result = value.strip().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }
}
