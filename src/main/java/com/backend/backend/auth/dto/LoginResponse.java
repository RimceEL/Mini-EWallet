package com.backend.backend.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {
    private String refreshToken;
    private String accessToken;
}
