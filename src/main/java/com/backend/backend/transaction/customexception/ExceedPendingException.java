package com.backend.backend.transaction.customexception;

public class ExceedPendingException extends RuntimeException {

    public ExceedPendingException(String message) {
        super(message);
    }

}
