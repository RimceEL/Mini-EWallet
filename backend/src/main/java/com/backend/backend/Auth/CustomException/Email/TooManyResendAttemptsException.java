package com.backend.backend.Auth.CustomException.Email;

public class TooManyResendAttemptsException extends RuntimeException {

    public TooManyResendAttemptsException(String message) {
        super(message);
    }

}
