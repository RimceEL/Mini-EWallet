package com.backend.backend.Auth.CustomException.Authentication;

public class UserExistedException extends RuntimeException {
    public UserExistedException(String message) {
        super(message);
    }
}
