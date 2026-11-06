package com.backend.backend.auth.customexception.token;

import org.springframework.security.core.AuthenticationException;

public class ExpiredRefreshTokenException extends AuthenticationException {
    public ExpiredRefreshTokenException(String message) {
        super(message);
    }
}
