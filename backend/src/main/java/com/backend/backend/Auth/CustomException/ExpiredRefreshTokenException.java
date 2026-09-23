package com.backend.backend.Auth.CustomException;

import org.springframework.security.core.AuthenticationException;

public class ExpiredRefreshTokenException extends AuthenticationException {
    public ExpiredRefreshTokenException(String message) {
        super(message);
    }
}
