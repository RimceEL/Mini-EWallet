package com.backend.backend.Auth.CustomException.Token;

import org.springframework.security.core.AuthenticationException;

public class RevokingException extends AuthenticationException {
    public RevokingException(String message) {
        super(message);
    }
}
