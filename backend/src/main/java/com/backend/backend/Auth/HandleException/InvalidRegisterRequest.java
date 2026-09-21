package com.backend.backend.Auth.HandleException;

public class InvalidRegisterRequest extends RuntimeException {
    public InvalidRegisterRequest(String message) {
        super(message);
    }
}
