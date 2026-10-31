package com.backend.backend.Auth.CustomException.Token;

import org.springframework.security.core.AuthenticationException;

public class InvalidRefreshToken extends AuthenticationException {
    public InvalidRefreshToken(String message) {
        super(message);
    }
}
