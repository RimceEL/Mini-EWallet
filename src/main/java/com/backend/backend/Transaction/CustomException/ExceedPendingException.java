package com.backend.backend.Transaction.CustomException;

public class ExceedPendingException extends RuntimeException {

    public ExceedPendingException(String message) {
        super(message);
    }

}
