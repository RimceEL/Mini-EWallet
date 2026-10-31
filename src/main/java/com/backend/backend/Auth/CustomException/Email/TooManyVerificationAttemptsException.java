package com.backend.backend.Auth.CustomException.Email;

public class TooManyVerificationAttemptsException extends RuntimeException {
    public TooManyVerificationAttemptsException(String message) {
        super(message);
    }
}
