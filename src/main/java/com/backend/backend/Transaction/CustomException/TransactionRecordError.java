package com.backend.backend.transaction.customexception;

public class TransactionRecordError extends RuntimeException {

    public TransactionRecordError(String message) {
        super(message);
    }

}
