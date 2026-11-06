package com.backend.backend.wallet.customexception;

public class WalletOpenedException extends RuntimeException {

    public WalletOpenedException(String message) {
        super(message);
    }

}
