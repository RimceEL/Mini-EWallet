package com.backend.backend.Auth.DTO;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OtpVerifyResponse {
    private String token;

}
