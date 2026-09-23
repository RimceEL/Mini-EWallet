package com.backend.backend.Auth.CustomException;

public class AddUserError extends RuntimeException {
    public AddUserError(String message) {
        super(message);
    }
}
