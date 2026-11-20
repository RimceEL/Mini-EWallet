package com.backend.backend.auth.security.token;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.auth.config.SecurityConfig;
import com.backend.backend.auth.customexception.CustomAccessDeniedHandler;
import com.backend.backend.auth.entrypoint.JwtAuthenticationEntryPoint;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.JwtService;
import com.backend.backend.auth.service.MyUserDetailsService;
import com.backend.backend.auth.service.RefreshTokenService;
import com.backend.backend.user.UserController;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@WebMvcTest(controllers = UserController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        JwtService.class,
        SecurityFilterChainTest.AdminPingController.class })
@TestPropertySource(properties = {
        "jwt.secret=" + SecurityFilterChainTest.SECRET,
        "jwt.access.expiration=60000",
        "jwt.refresh.expiration=600000",
        "password.strength=4"
})
public class SecurityFilterChainTest {

    static final String SECRET = "test-secret-test-secret-test-secret-12345";

    private static final String USER_URL = "/api/v1/user/user-details";
    private static final String ADMIN_URL = "/api/v1/admin/ping";
    private static final String EMAIL = "an@gmail.com";
    private static final String JTI = "jti-1";

    @RestController
    @RequestMapping("/api/v1/admin")
    public static class AdminPingController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private MyUserDetailsService userDetailsService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private static User userWithRole(Role role) {
        return user(EMAIL, role, 1, 1);
    }

    private static User user(String email, Role role, int isActive, int isVerified) {
        return User.builder()
                .id("user-1")
                .email(email)
                .username("tester01")
                .password("khong-dung-toi")
                .role(role)
                .fullName("Nguyen Van An")
                .isActive(isActive)
                .isVerified(isVerified)
                .build();
    }

    private void stubSession(User user) {
        when(userDetailsService.loadUserByUsername(user.getEmail())).thenReturn(user);
        when(refreshTokenService.isExist(user.getEmail(), JTI)).thenReturn(true);
    }

    private String loginAs(User user) {
        stubSession(user);
        return "Bearer " + jwtService.accessTokenGenerate(user, JTI);
    }

    private static Date inOneMinute() {
        return new Date(System.currentTimeMillis() + 60_000);
    }

    private static Map<String, Object> claims(String role, String jti) {
        Map<String, Object> c = new HashMap<>();
        if (role != null) {
            c.put("roles", List.of(Map.of("authority", "ROLE_" + role)));
        }
        if (jti != null) {
            c.put("jti", jti);
        }
        return c;
    }

    private static String forgeToken(String secret, String subject, Map<String, Object> claims, Date expiration) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder().claims(claims).issuedAt(new Date()).expiration(expiration);
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String tamperPayload(String jwt, String from, String to) {
        String[] parts = jwt.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains(from), "Payload không chứa '" + from + "' nên không sửa được: " + payload);
        return parts[0] + "." + base64Url(payload.replace(from, to)) + "." + parts[2];
    }

    @Test
    @DisplayName("Không gửi token => 401 UNAUTHORIZED")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get(USER_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Token là chuỗi rác => 401 TOKEN_INVALID")
    void garbageToken_returns401() throws Exception {
        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer day-khong-phai-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Đường dẫn không khai báo, không có token => 401 (mặc định chặn, không lộ 404)")
    void unknownPath_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/khong-ton-tai"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Đường dẫn /api/v1/auth/** là công khai: không đòi token (không có handler nên 404, không phải 401)")
    void authPath_isPublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/khong-ton-tai"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USER có token hợp lệ => 200 trên API của user")
    void validUserToken_canAccessUserEndpoint() throws Exception {
        String bearer = loginAs(userWithRole(Role.USER));

        mockMvc.perform(get(USER_URL).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("USER gọi API của ADMIN => 403 Forbidden")
    void userToken_cannotAccessAdminEndpoint() throws Exception {
        String bearer = loginAs(userWithRole(Role.USER));

        mockMvc.perform(get(ADMIN_URL).header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("ADMIN gọi API của ADMIN => 200")
    void adminToken_canAccessAdminEndpoint() throws Exception {
        String bearer = loginAs(userWithRole(Role.ADMIN));

        mockMvc.perform(get(ADMIN_URL).header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[Đối chứng] Token tự dựng bằng đúng khoá và đủ claim => được chấp nhận (200)")
    void control_forgedWithCorrectKeyAndValidClaims_isAccepted() throws Exception {
        stubSession(userWithRole(Role.USER));
        String token = forgeToken(SECRET, EMAIL, claims("USER", JTI), inOneMinute());

        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Token ký bằng khoá khác, tự xưng ADMIN => 401 và không chạm tới DB/Redis")
    void tokenSignedWithWrongKey_returns401() throws Exception {
        stubSession(userWithRole(Role.ADMIN));
        String token = forgeToken("another-secret-another-secret-another-123", EMAIL, claims("ADMIN", JTI),
                inOneMinute());

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(refreshTokenService, never()).isExist(anyString(), anyString());
    }

    @Test
    @DisplayName("Token alg=none (không chữ ký) tự xưng ADMIN => 401")
    void algNoneToken_returns401() throws Exception {
        stubSession(userWithRole(Role.ADMIN));
        long exp = System.currentTimeMillis() / 1000 + 60;
        String token = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + base64Url("{\"sub\":\"" + EMAIL + "\",\"jti\":\"" + JTI
                        + "\",\"roles\":[{\"authority\":\"ROLE_ADMIN\"}],\"exp\":" + exp + "}")
                + ".";

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Sửa 'sub' sang email của admin nhưng giữ chữ ký cũ => 401")
    void tamperedSubject_returns401() throws Exception {
        String attackerBearer = loginAs(userWithRole(Role.USER));
        stubSession(user("admin@gmail.com", Role.ADMIN, 1, 1));
        String tampered = tamperPayload(attackerBearer.substring(7), EMAIL, "admin@gmail.com");

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        verify(userDetailsService, never()).loadUserByUsername("admin@gmail.com");
    }

    @Test
    @DisplayName("Sửa claim roles từ USER thành ADMIN nhưng giữ chữ ký cũ => 401")
    void tamperedRoleClaim_returns401() throws Exception {
        String bearer = loginAs(userWithRole(Role.USER));
        String tampered = tamperPayload(bearer.substring(7), "ROLE_USER", "ROLE_ADMIN");

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Token đã hết hạn => 401 TOKEN_EXPIRED")
    void expiredToken_returns401() throws Exception {
        stubSession(userWithRole(Role.USER));
        String token = forgeToken(SECRET, EMAIL, claims("USER", JTI), new Date(System.currentTimeMillis() - 60_000));

        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("Token thiếu claim jti => 401")
    void tokenWithoutJti_returns401() throws Exception {
        stubSession(userWithRole(Role.USER));
        String token = forgeToken(SECRET, EMAIL, claims("USER", null), inOneMinute());

        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Token thiếu subject (email) => 401")
    void tokenWithoutSubject_returns401() throws Exception {
        stubSession(userWithRole(Role.USER));
        String token = forgeToken(SECRET, null, claims("USER", JTI), inOneMinute());

        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Refresh token (không có claim roles) dùng làm access token => 401 NOT_USED_ACCESS_TOKEN")
    void refreshTokenUsedAsAccessToken_returns401() throws Exception {
        User user = userWithRole(Role.USER);
        stubSession(user);
        String refreshToken = jwtService.refreshTokenGenerate(user, JTI);

        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NOT_USED_ACCESS_TOKEN"));
    }

    @Test
    @DisplayName("Header 'Bearer ' nhưng token rỗng => 401")
    void emptyBearerToken_returns401() throws Exception {
        mockMvc.perform(get(USER_URL).header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Tiền tố 'bearer' viết thường bị bỏ qua => 401 như chưa gửi token")
    void lowercaseBearerPrefix_returns401() throws Exception {
        String bearer = loginAs(userWithRole(Role.USER));
        String lowercase = "bearer " + bearer.substring(7);

        mockMvc.perform(get(USER_URL).header("Authorization", lowercase))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("Phiên đã bị thu hồi trong Redis => 401 TOKEN_REVOKED")
    void revokedSession_returns401() throws Exception {
        String bearer = loginAs(userWithRole(Role.USER));
        when(refreshTokenService.isExist(EMAIL, JTI)).thenReturn(false);

        mockMvc.perform(get(USER_URL).header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    @DisplayName("User đã bị xoá khỏi DB => 401")
    void userDeletedFromDb_returns401() throws Exception {
        String bearer = loginAs(userWithRole(Role.USER));
        when(userDetailsService.loadUserByUsername(EMAIL)).thenThrow(new UsernameNotFoundException("đã xoá"));

        mockMvc.perform(get(USER_URL).header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("User bị khoá sau khi token được phát => 403 USER_IS_BANNED")
    void bannedUser_returns403() throws Exception {
        String bearer = loginAs(user(EMAIL, Role.USER, 0, 1));

        mockMvc.perform(get(USER_URL).header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_IS_BANNED"));
    }

    @Test
    @DisplayName("User chưa xác thực email => 401 USER_IS_NOT_VERIFIED")
    void unverifiedUser_returns401() throws Exception {
        String bearer = loginAs(user(EMAIL, Role.USER, 1, 0));

        mockMvc.perform(get(USER_URL).header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_IS_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("Quyền lấy từ DB, không từ token: token ghi ADMIN (chữ ký hợp lệ) nhưng DB là USER => 403")
    void roleClaimSaysAdmin_butDbSaysUser_returns403() throws Exception {
        stubSession(userWithRole(Role.USER));
        String token = forgeToken(SECRET, EMAIL, claims("ADMIN", JTI), inOneMinute());

        mockMvc.perform(get(ADMIN_URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }
}