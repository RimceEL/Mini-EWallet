package com.backend.backend.Auth.CustomException;

import org.springframework.security.core.AuthenticationException;

public class InvalidRefreshToken extends AuthenticationException {
    public InvalidRefreshToken(String message) {
        super(message);
    }
}
