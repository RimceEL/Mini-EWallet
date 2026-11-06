package com.backend.backend.auth.customexception.email;

public class TooManyVerificationAttemptsException extends RuntimeException {
    public TooManyVerificationAttemptsException(String message) {
        super(message);
    }
}
