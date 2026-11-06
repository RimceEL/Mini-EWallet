package com.backend.backend.auth.customexception.otp;

public class ManyOtpException extends RuntimeException {

    public ManyOtpException(String message) {
        super(message);
    }

}
