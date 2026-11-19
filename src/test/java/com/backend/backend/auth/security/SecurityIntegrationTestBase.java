package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.service.EmailService;
import com.backend.backend.auth.service.JwtService;
import com.jayway.jsonpath.JsonPath;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityIntegrationTestBase.TestInfrastructure.class)
@Timeout(value = 300, unit = TimeUnit.SECONDS)
public abstract class SecurityIntegrationTestBase {

    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.1"))
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    static final String JWT_SECRET = "integration-secret-integration-secret-12345";
    static final int ACCESS_TTL_MS = 300_000;
    static final int REFRESH_TTL_MS = 3_600_000;
    static final int LOCK_ATTEMPTS_1 = 3;
    static final int LOCK_ATTEMPTS_2 = 6;
    static final int LOCK_ATTEMPTS_3 = 9;
    static final int LOCK_MINUTES_1 = 1;
    static final int LOCK_MINUTES_2 = 5;
    static final int LOCK_MINUTES_3 = 1440;

    static final String USER_URL = "/api/v1/user/user-details";
    static final String ADMIN_URL = "/api/v1/admin/ping";
    static final String PASSWORD = "MatKhauDuMuoiHaiKyTu!1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");

        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("jwt.access.expiration", () -> ACCESS_TTL_MS);
        registry.add("jwt.refresh.expiration", () -> REFRESH_TTL_MS);
        registry.add("password.strength", () -> 4);

        registry.add("app.login.max-attempts-1", () -> LOCK_ATTEMPTS_1);
        registry.add("app.login.max-attempts-2", () -> LOCK_ATTEMPTS_2);
        registry.add("app.login.max-attempts-3", () -> LOCK_ATTEMPTS_3);
        registry.add("app.login.expiration-minutes-1", () -> LOCK_MINUTES_1);
        registry.add("app.login.expiration-minutes-2", () -> LOCK_MINUTES_2);
        registry.add("app.login.expiration-minutes-3", () -> LOCK_MINUTES_3);
        registry.add("app.login.attempt.duration-minutes", () -> 60);

        registry.add("app.otp.secret", () -> "test-otp-secret-test-otp-secret-123");
        registry.add("spring.mail.my-email", () -> "noreply@test.local");
        registry.add("spring.mail.host", () -> "localhost");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInfrastructure {

        @Bean
        @Primary
        RedisConnectionFactory testRedisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }

        @Bean
        AdminPingController adminPingController() {
            return new AdminPingController();
        }
    }

    @TestComponent
    @RestController
    @RequestMapping("/api/v1/admin")
    public static class AdminPingController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JwtService jwtService;

    @MockitoBean
    protected EmailService emailService;

    @BeforeEach
    void resetState() {
        Set<String> keys = redis.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                List<String> tables = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                                + "AND table_name <> 'flyway_schema_history'")) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }

                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String table : tables) {
                        st.execute("TRUNCATE TABLE `" + table + "`");
                    }
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }

    protected record Tokens(String access, String refresh) {
    }

    protected void createUser(String email, String rawPassword, Role role, int isActive, int isVerified) {
        createUserWithHash(email, passwordEncoder.encode(rawPassword), role, isActive, isVerified);
    }

    protected void createUserWithHash(String email, String passwordHash, Role role, int isActive,
            int isVerified) {
        jdbc.update("INSERT INTO users (email, username, password, role, full_name, is_active, is_verified) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                email, "user_" + UUID.randomUUID().toString().substring(0, 8), passwordHash, role.name(),
                "Nguyen Van Test", isActive, isVerified);
    }

    protected void setRole(String email, Role role) {
        jdbc.update("UPDATE users SET role = ? WHERE email = ?", role.name(), email);
    }

    protected void setActive(String email, int isActive) {
        jdbc.update("UPDATE users SET is_active = ? WHERE email = ?", isActive, email);
    }

    protected void setVerified(String email, int isVerified) {
        jdbc.update("UPDATE users SET is_verified = ? WHERE email = ?", isVerified, email);
    }

    protected MvcResult postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    protected MvcResult loginRaw(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    protected Tokens loginOk(String email, String password) throws Exception {
        MvcResult result = loginRaw(email, password);
        assertThat(statusOf(result)).as("đăng nhập phải thành công: " + body(result)).isEqualTo(200);
        return parseTokens(result);
    }

    protected MvcResult refreshRaw(String refreshToken) throws Exception {
        return postJson("/api/v1/auth/refresh-token", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    protected Tokens refreshOk(String refreshToken) throws Exception {
        MvcResult result = refreshRaw(refreshToken);
        assertThat(statusOf(result)).as("refresh phải thành công: " + body(result)).isEqualTo(200);
        return parseTokens(result);
    }

    protected MvcResult logoutRaw(String refreshToken) throws Exception {
        return postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    protected MvcResult getWithToken(String url, String accessToken) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    protected static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected static String jsonString(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    protected static Tokens parseTokens(MvcResult result) throws Exception {
        String json = body(result);
        String access = JsonPath.read(json, "$.data.accessToken");
        String refresh = JsonPath.read(json, "$.data.refreshToken");
        return new Tokens(access, refresh);
    }

    protected Set<String> sessionKeys(String email) {
        return redis.keys("refresh_token:" + email + ":*");
    }

    protected static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static Map<String, Object> claims(String jti) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("jti", jti);
        return claims;
    }

    protected static String forgeToken(String secret, String subject, Map<String, Object> claims,
            Date expiration) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder().claims(claims).issuedAt(new Date()).expiration(expiration);
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    protected static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    protected static String payloadOf(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    protected static String tamperPayload(String jwt, String from, String to) {
        String[] parts = jwt.split("\\.");
        String payload = payloadOf(jwt);
        assertThat(payload).as("payload phải chứa '" + from + "' mới sửa được").contains(from);
        return parts[0] + "." + base64Url(payload.replace(from, to)) + "." + parts[2];
    }
}
