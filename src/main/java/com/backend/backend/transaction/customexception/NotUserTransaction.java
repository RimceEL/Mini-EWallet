package com.backend.backend.transaction.customexception;

public class NotUserTransaction extends RuntimeException {

    public NotUserTransaction(String message) {
        super(message);
    }

}
