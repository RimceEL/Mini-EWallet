package com.backend.backend.Wallet;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.Auth.Model.User;
import com.backend.backend.DTO.ApiResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;

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

}
