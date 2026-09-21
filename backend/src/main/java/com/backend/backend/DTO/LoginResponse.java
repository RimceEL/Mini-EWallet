package com.backend.backend.DTO;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {
    private String refreshToken;
    private String accessToken;
}
