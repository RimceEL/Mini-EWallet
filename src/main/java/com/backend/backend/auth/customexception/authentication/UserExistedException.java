package com.backend.backend.auth.customexception.authentication;

public class UserExistedException extends RuntimeException {
    public UserExistedException(String message) {
        super(message);
    }
}
