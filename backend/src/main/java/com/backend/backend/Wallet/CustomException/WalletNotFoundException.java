package com.backend.backend.Wallet.CustomException;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(String message) {
        super(message);
    }

}
