package com.backend.backend.Auth.CustomException.Authentication;

public class InvalidRegisterRequest extends RuntimeException {
    public InvalidRegisterRequest(String message) {
        super(message);
    }
}
