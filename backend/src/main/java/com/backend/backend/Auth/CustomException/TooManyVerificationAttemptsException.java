package com.backend.backend.Auth.CustomException;

public class TooManyVerificationAttemptsException extends RuntimeException {
    public TooManyVerificationAttemptsException(String message) {
        super(message);
    }
}
