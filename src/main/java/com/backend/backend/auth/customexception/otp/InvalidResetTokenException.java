package com.backend.backend.auth.customexception.otp;

public class InvalidResetTokenException extends RuntimeException {
    public InvalidResetTokenException() {
        super("Invalid or expired reset token");
    }

    public InvalidResetTokenException(String message) {
        super(message);
    }

}
