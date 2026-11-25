package com.backend.backend.auth.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backend.backend.auth.filter.JwtAuthenticationFilter;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.AuthService;
import com.backend.backend.auth.service.JwtService;
import com.backend.backend.auth.service.MyUserDetailsService;
import com.backend.backend.auth.service.PasswordResetService;
import com.backend.backend.auth.service.RefreshTokenService;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
public class AuthControllerTest {

    private static final String EMAIL = "an@gmail.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private RefreshTokenService refreshTokenService;
    @MockitoBean
    private PasswordResetService passwordResetService;
    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean
    private MyUserDetailsService myUserDetailsService;

    @Test
    @DisplayName("refresh-token: khi revoke() trả false (bị request khác thu hồi ngay giữa chừng) => 401 RevokingException")
    void refresh_whenRevokeReturnsFalse_returns401RevokingMessage() throws Exception {
        User user = User.builder().email(EMAIL).role(Role.USER).isActive(1).isVerified(1).build();
        when(jwtService.extractUsername("rt")).thenReturn(EMAIL);
        when(jwtService.extractJti("rt")).thenReturn("jti-1");
        when(refreshTokenService.isUsed(EMAIL, "rt")).thenReturn(false);
        when(refreshTokenService.isValid(EMAIL, "jti-1", "rt")).thenReturn(true);
        when(authService.findUserByEmail(EMAIL)).thenReturn(user);
        when(refreshTokenService.revoke(EMAIL, "jti-1")).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"rt\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("RefreshToken đang thu hồi bởi một request khác"));
    }

    @Test
    @DisplayName("refresh-token: user bị đánh dấu chưa xác thực (!isEnabled) => 401 InvalidRefreshToken")
    void refresh_whenUserUnverified_returns401() throws Exception {
        User unverified = User.builder().email(EMAIL).role(Role.USER).isActive(1).isVerified(0).build();
        when(jwtService.extractUsername("rt")).thenReturn(EMAIL);
        when(jwtService.extractJti("rt")).thenReturn("jti-1");
        when(refreshTokenService.isUsed(EMAIL, "rt")).thenReturn(false);
        when(refreshTokenService.isValid(EMAIL, "jti-1", "rt")).thenReturn(true);
        when(authService.findUserByEmail(EMAIL)).thenReturn(unverified);

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"rt\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh Token không hợp lệ"));
    }

    @Test
    @DisplayName("logout: validateAndRevoke trả false (hash không khớp) => 401 InvalidRefreshToken")
    void logout_whenValidateAndRevokeReturnsFalse_returns401() throws Exception {
        when(jwtService.extractUsername("rt")).thenReturn(EMAIL);
        when(jwtService.extractJti("rt")).thenReturn("jti-1");
        when(refreshTokenService.validateAndRevoke(EMAIL, "jti-1", "rt")).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"rt\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh Token không hợp lệ"));
    }

    @Test
    @DisplayName("verify-email & resend-verification: chuẩn hoá email (trim + lowercase) trước khi gọi AuthService")
    void verifyAndResend_normalizeEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  AN@GMAIL.COM  \",\"code\":\"123456\"}"))
                .andExpect(status().isOk());
        verify(authService).verifyEmail(EMAIL, "123456");

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  AN@GMAIL.COM  \"}"))
                .andExpect(status().isOk());
        verify(authService).resendVerificationCode(EMAIL);
    }

    @Test
    @DisplayName("otp-reset-*: chuẩn hoá email (trim + lowercase) ở cả 3 bước đặt lại mật khẩu")
    void otpResetEndpoints_normalizeEmail() throws Exception {
        when(passwordResetService.verifyOtp(anyString(), anyString())).thenReturn("reset-token");

        mockMvc.perform(post("/api/v1/auth/otp-reset-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  AN@GMAIL.COM  \"}"))
                .andExpect(status().isOk());
        verify(passwordResetService).requestReset(EMAIL);

        mockMvc.perform(post("/api/v1/auth/otp-reset-verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  AN@GMAIL.COM  \",\"code\":\"12345678\"}"))
                .andExpect(status().isOk());
        verify(passwordResetService).verifyOtp(EMAIL, "12345678");

        mockMvc.perform(post("/api/v1/auth/otp-reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  AN@GMAIL.COM  \",\"token\":\"reset-token\",\"newPassword\":\"MatKhauMoiDuDai!1\"}"))
                .andExpect(status().isOk());
        verify(passwordResetService).resetPassword(EMAIL, "reset-token", "MatKhauMoiDuDai!1");
    }
}
