package com.backend.backend.auth.customexception.token;

import org.springframework.security.core.AuthenticationException;

public class InvalidRefreshToken extends AuthenticationException {
    public InvalidRefreshToken(String message) {
        super(message);
    }
}
