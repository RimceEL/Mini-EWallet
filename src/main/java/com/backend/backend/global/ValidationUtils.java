package com.backend.backend.global;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class ValidationUtils {
    private static final String EMAIL_REGEX = "^[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*"
            + "@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);
    private static final String PASSWORD_REGEX = "^(?=.*\\p{Lu})(?=.*[\\p{P}\\p{S}]).{12,}$";
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(PASSWORD_REGEX);

    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        if (email.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidPassword(String password) {
        if (password == null) {
            return false;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return PASSWORD_PATTERN.matcher(password).matches();
    }

    public static boolean isValidUsername(String username) {
        if (username == null) {
            return false;
        }
        if (username.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return username.strip().length() >= 6;
    }

    public static boolean isValidFullName(String fullName) {
        if (fullName == null) {
            return false;
        }
        if (fullName.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return fullName.strip().length() >= 6;
    }
}
