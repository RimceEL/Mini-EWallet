package com.backend.backend.Auth.HandleException;

public class AddUserError extends RuntimeException {
    public AddUserError(String message) {
        super(message);
    }
}
