package com.backend.backend.auth.customexception.token;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;

public class RefreshTokenIsUsedException extends AuthenticationException {

    public RefreshTokenIsUsedException(@Nullable String msg) {
        super(msg);
    }

}
