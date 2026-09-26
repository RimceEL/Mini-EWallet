package com.backend.backend.Auth.Filter;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backend.backend.Auth.Service.JwtService;
import com.backend.backend.Auth.Service.MyUserDetailsService;
import com.backend.backend.Auth.Service.RefreshTokenService;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final MyUserDetailsService userDetailsService;
    private final RefreshTokenService refreshTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;
        final String jti;
        final List<String> role;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            jwt = authHeader.substring(7);
            userEmail = jwtService.extractUsername(jwt);
            jti = jwtService.extractJti(jwt);
            role = jwtService.extractRoles(jwt);

            if (role.size() == 0) {
                request.setAttribute("jwt_error", "NOT_USED_ACCESS_TOKEN");
                filterChain.doFilter(request, response);
                return;
            }

            if (userEmail == null || jti == null) {
                request.setAttribute("jwt_error", "TOKEN_INVALID");
                filterChain.doFilter(request, response);
                return;
            }

            if (!refreshTokenService.isExist(userEmail, jti)) {
                request.setAttribute("jwt_error", "TOKEN_REVOKED");
                filterChain.doFilter(request, response);
                return;
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
                boolean isValid = jwtService.isTokenValid(jwt, userDetails);
                if (isValid && userDetails.isEnabled()) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else if (!isValid) {
                    request.setAttribute("jwt_error", "TOKEN_INVALID");
                } else {
                    request.setAttribute("jwt_error", "USER_IS_BANNED");
                }
            }
        } catch (ExpiredJwtException e) {
            request.setAttribute("jwt_error", "TOKEN_EXPIRED");
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            request.setAttribute("jwt_error", "TOKEN_INVALID");
        }

        filterChain.doFilter(request, response);
    }

}

// Entry point không do filter JWT của bạn gọi, mà do ExceptionTranslationFilter
// gọi, và filter này chỉ bắt lỗi phát sinh từ các thành phần đứng sau nó trong
// chain:

// JwtFilter (của bạn) → ... → ExceptionTranslationFilter → AuthorizationFilter
// → Controller

// Nên có 4 trường hợp:

// Filter JWT ném exception (kể cả AuthenticationException): không vào entry
// point, vì JwtFilter đứng trước ExceptionTranslationFilter nên không ai bắt.
// Kết quả thường là 500 hoặc trang lỗi mặc định. Đây chính là lý do ở câu trước
// mình chọn cách filter không ném lỗi.
// Filter không set Authentication (token thiếu, sai, hết hạn) rồi vẫn
// chain.doFilter: nếu endpoint yêu cầu đăng nhập, AuthorizationFilter sẽ ném
// AccessDeniedException. ExceptionTranslationFilter thấy user đang là anonymous
// nên gọi AuthenticationEntryPoint → 401. Đây là luồng chạy đúng. Nếu endpoint
// là permitAll thì không có gì được gọi cả, request đi qua bình thường.
// Đã đăng nhập hợp lệ nhưng thiếu quyền (ví dụ USER gọi API của ADMIN): vào
// AccessDeniedHandler → 403, không phải entry point.
// Filter tự ghi response rồi return: không qua entry point, response do bạn tự
// viết.
