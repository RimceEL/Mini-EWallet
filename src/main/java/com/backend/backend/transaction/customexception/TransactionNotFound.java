package com.backend.backend.transaction.customexception;

public class TransactionNotFound extends RuntimeException {

    public TransactionNotFound(String message) {
        super(message);
    }

}
