package com.backend.backend.Auth.HandleException;

import org.springframework.security.core.AuthenticationException;

public class ExpiredRefreshTokenException extends AuthenticationException {
    public ExpiredRefreshTokenException(String message) {
        super(message);
    }
}
