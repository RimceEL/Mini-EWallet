package com.backend.backend.Auth.CustomException.Email;

public class ExpiredVerificationCodeException extends RuntimeException {
    public ExpiredVerificationCodeException(String message) {
        super(message);
    }
}
