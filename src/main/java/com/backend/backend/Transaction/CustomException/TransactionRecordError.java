package com.backend.backend.Transaction.CustomException;

public class TransactionRecordError extends RuntimeException {

    public TransactionRecordError(String message) {
        super(message);
    }

}
