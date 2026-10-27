package com.backend.backend.Transaction.CustomException;

public class NotUserTransaction extends RuntimeException {

    public NotUserTransaction(String message) {
        super(message);
    }

}
