package com.backend.backend.user;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.auth.model.User;
import com.backend.backend.dto.ApiResponse;
import com.backend.backend.user.dto.UserDetailResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserController {
    @GetMapping("/user/user-details")
    public ApiResponse<UserDetailResponse> getUserDetails(@AuthenticationPrincipal User user) {
        UserDetailResponse result = UserDetailResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .address(user.getAddress())
                .role(user.getRole())
                .build();
        return new ApiResponse<>(0, "Success", result);
    }
}
