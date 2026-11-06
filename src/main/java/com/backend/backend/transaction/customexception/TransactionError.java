package com.backend.backend.transaction.customexception;

public class TransactionError extends RuntimeException {

    public TransactionError(String message) {
        super(message);
    }

}
