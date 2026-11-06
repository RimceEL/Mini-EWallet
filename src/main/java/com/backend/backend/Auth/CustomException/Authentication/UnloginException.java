package com.backend.backend.auth.customexception.authentication;

import org.springframework.security.core.AuthenticationException;

public class UnloginException extends AuthenticationException {
    public UnloginException(String message) {
        super(message);
    }

}
