package com.backend.backend.Auth.CustomException;

import org.springframework.security.core.AuthenticationException;

public class UnloginException extends AuthenticationException {
    public UnloginException(String message) {
        super(message);
    }

}
