package com.backend.backend.Wallet;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.Auth.Model.User;
import com.backend.backend.DTO.ApiResponse;
import com.backend.backend.Wallet.DTO.WalletDetailResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/")
public class WalletController {
    private final WalletService walletService;

    @PostMapping("/user/open-wallet")
    public ApiResponse<String> openWallet(@AuthenticationPrincipal User user) {
        walletService.openWallet(user.getEmail());
        return new ApiResponse<String>(0, "Success", null);
    }

    @GetMapping("/user/wallet-details")
    public ApiResponse<WalletDetailResponse> getWalletDetails(@AuthenticationPrincipal User user) {
        Wallet wallet = walletService.getWalletDetails(user.getEmail());
        WalletDetailResponse result = WalletDetailResponse.builder()
                .id(wallet.getId())
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .status(wallet.getStatus())
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .build();
        return new ApiResponse<>(0, "Successful", result);
    }

}
