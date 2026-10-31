package com.backend.backend.Auth.Service;

import java.util.regex.Pattern;

public class ValidationUtils {
    private static final String EMAIL_REGEX = "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);

    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidPassword(String password) {
        if (password == null) {
            return false;
        }
        return password.length() >= 12;
    }

    public static boolean isValidUsername(String username) {
        if (username == null) {
            return false;
        }
        return username.trim().length() >= 6;
    }

    public static boolean isValidFullName(String fullName) {
        if (fullName == null) {
            return false;
        }
        return fullName.trim().length() >= 6;
    }
}
