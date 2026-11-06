package com.backend.backend.auth.customexception.token;

import org.springframework.security.core.AuthenticationException;

public class RevokingException extends AuthenticationException {
    public RevokingException(String message) {
        super(message);
    }
}
