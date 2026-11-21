package com.backend.backend.wallet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.backend.backend.wallet.customexception.UserNotFoundException;
import com.backend.backend.wallet.customexception.WalletNotFoundException;
import com.backend.backend.wallet.customexception.WalletOpenedException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;

    public void openWallet(String userEmail) {
        try {
            if (userEmail == null || userEmail.isBlank() || walletRepository.createWalletByUserEmail(userEmail) == 0)
                throw new UserNotFoundException("User is not existed");
        } catch (DuplicateKeyException e) {
            throw new WalletOpenedException("This user has already open wallet");
        }
    }

    public Wallet getWalletDetails(String userEmail) {
        return walletRepository.findWalletByUserEmail(userEmail)
                .orElseThrow(
                        () -> new WalletNotFoundException("Email is not existed or User has not opened wallet yet"));
    }

}
