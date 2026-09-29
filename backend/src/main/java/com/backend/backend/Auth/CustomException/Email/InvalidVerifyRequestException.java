package com.backend.backend.Auth.CustomException.Email;

public class InvalidVerifyRequestException extends RuntimeException {

    public InvalidVerifyRequestException(String message) {
        super(message);
    }

}
