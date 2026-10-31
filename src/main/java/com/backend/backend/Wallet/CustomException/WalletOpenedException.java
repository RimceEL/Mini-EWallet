package com.backend.backend.Wallet.CustomException;

public class WalletOpenedException extends RuntimeException {

    public WalletOpenedException(String message) {
        super(message);
    }

}
