package com.backend.backend.Auth.Controller;

import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.Auth.CustomException.Authentication.UnloginException;
import com.backend.backend.Auth.CustomException.Token.ExpiredRefreshTokenException;
import com.backend.backend.Auth.CustomException.Token.InvalidRefreshToken;
import com.backend.backend.Auth.Model.User;
import com.backend.backend.Auth.Service.AuthService;
import com.backend.backend.Auth.Service.JwtService;
import com.backend.backend.Auth.Service.RefreshTokenService;
import com.backend.backend.DTO.ApiResponse;
import com.backend.backend.DTO.LoginRequest;
import com.backend.backend.DTO.LoginResponse;
import com.backend.backend.DTO.RefreshTokenRequest;
import com.backend.backend.DTO.RegisterRequest;
import com.backend.backend.DTO.ResendVerificationRequest;
import com.backend.backend.DTO.VerifyEmailRequest;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;

import java.util.Date;
import java.util.UUID;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        User user = authService.login(request);
        String jti = UUID.randomUUID().toString();
        String accessToken = jwtService.accessTokenGenerate(user, jti);
        String refreshToken = jwtService.refreshTokenGenerate(user, jti);
        String email = jwtService.extractUsername(refreshToken);
        refreshTokenService.revokeAll(email);
        refreshTokenService.store(email, jti, refreshToken);
        LoginResponse loginResponse = LoginResponse
                .builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
        return new ApiResponse<LoginResponse>(0, "Login successfully", loginResponse);
    }

    @PostMapping("/auth/register")
    public ApiResponse<String> register(@RequestBody RegisterRequest request) {
        authService.register(request);
        return new ApiResponse<String>(0, "Register successfully", "");
    }

    @PostMapping("/auth/refresh-token")
    public ApiResponse<LoginResponse> refresh(@RequestBody RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        String email;
        String oldJti;
        try {
            email = jwtService.extractUsername(refreshToken);
            oldJti = jwtService.extractJti(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new ExpiredRefreshTokenException("");
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            throw new InvalidRefreshToken("");
        }

        User user = authService.findUserByEmail(email);
        if (!refreshTokenService.isValid(email, oldJti, refreshToken))
            throw new UnloginException("");
        Date expiredDate = jwtService.extractExpiration(refreshToken);
        String jti = UUID.randomUUID().toString();
        String newAccessToken = jwtService.accessTokenGenerate(user, jti);
        String newRefreshToken = jwtService.refreshTokenGenerate(user, jti,
                expiredDate.getTime() - new Date().getTime());
        refreshTokenService.revoke(email, oldJti);
        refreshTokenService.store(email, jti, newRefreshToken);
        LoginResponse loginResponse = LoginResponse
                .builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
        return new ApiResponse<LoginResponse>(0, "Refresh Token successfully", loginResponse);
    }

    @PostMapping("/auth/logout")
    public String logout(@RequestBody RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        String email;
        String oldJti;
        try {
            email = jwtService.extractUsername(refreshToken);
            oldJti = jwtService.extractJti(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new ExpiredRefreshTokenException("");
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            throw new InvalidRefreshToken("");
        }
        if (!refreshTokenService.isValid(email, oldJti, refreshToken))
            throw new UnloginException("");
        refreshTokenService.revoke(email, oldJti);
        return "Logout Successfully";
    }

    // Lưu ý (logout): Nếu không truyền Bearer token (ACC hoặc RE), user sẽ là null
    // do không
    // có context holder set, hoặc khi đã đăng xuất, redis ko lưu trạng thái, nên
    // nếu có truyền token thì nó sẽ đi qua JwtFilter và bị chặn do ko match với
    // redis và cũng trả về user là null

    @PostMapping("/auth/verify-email")
    public ApiResponse<String> verifyEmail(@RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.getEmail(), request.getCode());
        return new ApiResponse<String>(0, "Xác thực email thành công", "");
    }

    @PostMapping("/auth/resend-verification")
    public ApiResponse<String> resendVerification(@RequestBody ResendVerificationRequest request) {
        authService.resendVerificationCode(request.getEmail());
        return new ApiResponse<String>(0, "Đã gửi lại mã xác thực", "");
    }

}
