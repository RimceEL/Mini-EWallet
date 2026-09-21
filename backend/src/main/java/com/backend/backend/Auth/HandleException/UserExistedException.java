package com.backend.backend.Auth.HandleException;

public class UserExistedException extends RuntimeException {
    public UserExistedException(String message) {
        super(message);
    }
}
