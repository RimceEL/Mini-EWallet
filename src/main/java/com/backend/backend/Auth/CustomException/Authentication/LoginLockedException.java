package com.backend.backend.auth.customexception.authentication;

public class LoginLockedException extends RuntimeException {
    public LoginLockedException(String message) {
        super(message);
    }
}
