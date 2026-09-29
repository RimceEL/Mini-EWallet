package com.backend.backend.Auth.CustomException;

public class TooManyResendAttemptsException extends RuntimeException {

    public TooManyResendAttemptsException(String message) {
        super(message);
    }

}
