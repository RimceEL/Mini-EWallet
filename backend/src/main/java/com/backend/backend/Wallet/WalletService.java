package com.backend.backend.Wallet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.backend.backend.Wallet.CustomException.UserNotFoundException;
import com.backend.backend.Wallet.CustomException.WalletNotFoundException;
import com.backend.backend.Wallet.CustomException.WalletOpenedException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;

    public void openWallet(String userEmail) {
        try {
            walletRepository.createWalletByUserEmail(userEmail);
        } catch (DuplicateKeyException e) {
            throw new WalletOpenedException("This user has already open wallet");
        } catch (DataIntegrityViolationException e) {
            throw new UserNotFoundException("User is not existed");
        }
    }

    public Wallet getWalletDetails(String userEmail) {
        return walletRepository.findWalletByUserEmail(userEmail)
                .orElseThrow(
                        () -> new WalletNotFoundException("Email is not existed or User has not opened wallet yet"));
    }

}
