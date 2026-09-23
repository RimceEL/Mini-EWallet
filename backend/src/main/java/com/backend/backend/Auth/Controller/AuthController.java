package com.backend.backend.Auth.Controller;

import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.Auth.CustomException.ExpiredRefreshTokenException;
import com.backend.backend.Auth.CustomException.InvalidRefreshToken;
import com.backend.backend.Auth.CustomException.UnloginException;
import com.backend.backend.Auth.Model.User;
import com.backend.backend.Auth.Service.AuthService;
import com.backend.backend.Auth.Service.JwtService;
import com.backend.backend.Auth.Service.RefreshTokenService;
import com.backend.backend.DTO.ApiResponse;
import com.backend.backend.DTO.LoginRequest;
import com.backend.backend.DTO.LoginResponse;
import com.backend.backend.DTO.RefreshTokenRequest;
import com.backend.backend.DTO.RegisterRequest;
import com.backend.backend.DTO.RegisterResponse;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;

import java.util.Date;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public ApiResponse<RegisterResponse> register(@RequestBody RegisterRequest request) {
        User user = authService.register(request);
        RegisterResponse registerResponse = RegisterResponse
                .builder().id(user.getId())
                .email(user.getEmail())
                .username(user.getName())
                .fullName(user.getFullName())
                .role(user.getRole())
                .isActive(user.isAccountNonLocked())
                .createdAt(user.getCreatedAt())
                .build();
        return new ApiResponse<RegisterResponse>(0, "Register successfully", registerResponse);
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
        refreshTokenService.revokeAll(email);
        refreshTokenService.store(email, jti, newRefreshToken);
        LoginResponse loginResponse = LoginResponse
                .builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
        return new ApiResponse<LoginResponse>(0, "Refresh Token successfully", loginResponse);
    }

    @PostMapping("/auth/logout")
    public String logout(@AuthenticationPrincipal User user) {
        if (user == null)
            throw new UnloginException("");
        String email = user.getEmail();
        refreshTokenService.revokeAll(email);
        return "Logout Successfully";
    }

    // Lưu ý (logout): Nếu không truyền Bearer token (ACC hoặc RE), user sẽ là null
    // do không
    // có context holder set, hoặc khi đã đăng xuất, redis ko lưu trạng thái, nên
    // nếu có truyền token thì nó sẽ đi qua JwtFilter và bị chặn do ko match với
    // redis và cũng trả về user là null

}
