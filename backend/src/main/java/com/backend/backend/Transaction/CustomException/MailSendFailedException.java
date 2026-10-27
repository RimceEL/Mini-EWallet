package com.backend.backend.Transaction.CustomException;

public class MailSendFailedException extends RuntimeException {

    public MailSendFailedException(String message) {
        super(message);
    }

}
