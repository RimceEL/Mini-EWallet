package com.backend.backend.Transaction.CustomException;

public class TransactionError extends RuntimeException {

    public TransactionError(String message) {
        super(message);
    }

}
