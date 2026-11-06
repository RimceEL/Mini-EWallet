package com.backend.backend.transaction.customexception;

public class NotEnoughAmountException extends RuntimeException {

    public NotEnoughAmountException(String message) {
        super(message);
    }

}
