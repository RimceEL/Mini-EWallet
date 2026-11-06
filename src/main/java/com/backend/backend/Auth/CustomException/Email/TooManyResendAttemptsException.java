package com.backend.backend.auth.customexception.email;

public class TooManyResendAttemptsException extends RuntimeException {

    public TooManyResendAttemptsException(String message) {
        super(message);
    }

}
