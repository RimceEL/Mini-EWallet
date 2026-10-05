package com.backend.backend.Auth.CustomException.Authentication;

public class LoginLockedException extends RuntimeException {
    public LoginLockedException(String message) {
        super(message);
    }
}
