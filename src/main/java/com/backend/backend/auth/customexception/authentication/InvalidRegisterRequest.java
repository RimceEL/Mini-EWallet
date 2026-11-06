package com.backend.backend.auth.customexception.authentication;

public class InvalidRegisterRequest extends RuntimeException {
    public InvalidRegisterRequest(String message) {
        super(message);
    }
}
