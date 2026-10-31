package com.backend.backend.Auth.EntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final tools.jackson.databind.ObjectMapper objectMapper;

    private record ErrorInfo(HttpStatus status, String message) {
    }

    @Override
    public void commence(HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        // Mã lỗi do JwtAuthenticationFilter đặt; null nghĩa là request không kèm token
        String code = (String) request.getAttribute("jwt_error");
        if (code == null) {
            code = "UNAUTHORIZED";
        }

        ErrorInfo info = switch (code) {
            case "TOKEN_EXPIRED" ->
                new ErrorInfo(HttpStatus.UNAUTHORIZED, "Access Token đã hết hạn");
            case "TOKEN_INVALID" ->
                new ErrorInfo(HttpStatus.UNAUTHORIZED, "Access Token không hợp lệ");
            case "TOKEN_REVOKED" ->
                new ErrorInfo(HttpStatus.UNAUTHORIZED, "Token đã bị thu hồi, vui lòng đăng nhập lại");
            case "USER_IS_BANNED" ->
                new ErrorInfo(HttpStatus.FORBIDDEN, "Tài khoản đã bị khoá");
            case "NOT_USED_ACCESS_TOKEN" ->
                new ErrorInfo(HttpStatus.UNAUTHORIZED, "Vui lòng sử dụng access token để truy cập tài nguyên này");
            case "USER_IS_NOT_VERIFIED" ->
                new ErrorInfo(HttpStatus.UNAUTHORIZED, "Tài khoản vẫn chưa được xác thực email");
            default ->
                new ErrorInfo(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để truy cập tài nguyên này");
        };

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", info.status().value());
        body.put("error", info.status().getReasonPhrase());
        body.put("code", code);
        body.put("message", info.message());

        response.setStatus(info.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}