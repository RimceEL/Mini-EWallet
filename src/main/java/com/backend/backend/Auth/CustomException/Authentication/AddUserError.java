package com.backend.backend.auth.customexception.authentication;

public class AddUserError extends RuntimeException {
    public AddUserError(String message) {
        super(message);
    }
}
