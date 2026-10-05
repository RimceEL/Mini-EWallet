package com.backend.backend.Auth.CustomException.Otp;

public class InvalidResetTokenException extends RuntimeException {
    public InvalidResetTokenException() {
        super("Invalid or expired reset token");
    }

    public InvalidResetTokenException(String message) {
        super(message);
    }

}
