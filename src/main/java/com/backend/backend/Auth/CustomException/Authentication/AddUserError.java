package com.backend.backend.Auth.CustomException.Authentication;

public class AddUserError extends RuntimeException {
    public AddUserError(String message) {
        super(message);
    }
}
