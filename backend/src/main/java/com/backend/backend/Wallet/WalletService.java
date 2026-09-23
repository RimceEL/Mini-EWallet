package com.backend.backend.Wallet;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

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
            throw new UsernameNotFoundException("User is not existed");
        }
    }

}
