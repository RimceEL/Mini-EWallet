package com.backend.backend.Auth.HandleException;

import org.springframework.security.core.AuthenticationException;

public class InvalidRefreshToken extends AuthenticationException {
    public InvalidRefreshToken(String message) {
        super(message);
    }
}
