package com.backend.backend.auth.customexception.email;

public class InvalidVerifyRequestException extends RuntimeException {

    public InvalidVerifyRequestException(String message) {
        super(message);
    }

}
