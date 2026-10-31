package com.backend.backend.Transaction.CustomException;

public class TransactionNotFound extends RuntimeException {

    public TransactionNotFound(String message) {
        super(message);
    }

}
