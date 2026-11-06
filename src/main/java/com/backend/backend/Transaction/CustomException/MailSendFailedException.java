package com.backend.backend.transaction.customexception;

public class MailSendFailedException extends RuntimeException {

    public MailSendFailedException(String message) {
        super(message);
    }

}
