package com.backend.backend.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OtpVerifyResponse {
    private String token;

}
