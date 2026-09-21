package com.backend.backend.Wallet;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.DTO.ApiResponse;

import lombok.RequiredArgsConstructor;

import java.nio.file.attribute.UserPrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;


@RestController 
@RequiredArgsConstructor
@RequestMapping("/api/v1/")
public class WalletController {
    @PostMapping("/user/open-wallet")
    public ApiResponse<String> postMethodName(@AuthenticationPrincipal UserPrincipal user) {
        return new ApiResponse<String>(0, "Success", null);
    }
    
}
