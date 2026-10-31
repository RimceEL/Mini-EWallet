package com.backend.backend.Transaction.CustomException;

public class NotEnoughAmountException extends RuntimeException {

    public NotEnoughAmountException(String message) {
        super(message);
    }

}
