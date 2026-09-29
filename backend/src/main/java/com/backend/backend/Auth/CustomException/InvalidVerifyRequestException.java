package com.backend.backend.Auth.CustomException;

public class InvalidVerifyRequestException extends RuntimeException {

    public InvalidVerifyRequestException(String message) {
        super(message);
    }

}
