package com.backend.backend.Auth.CustomException;

public class InvalidRegisterRequest extends RuntimeException {
    public InvalidRegisterRequest(String message) {
        super(message);
    }
}
