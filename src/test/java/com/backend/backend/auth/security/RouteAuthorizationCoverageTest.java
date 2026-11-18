package com.backend.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.backend.backend.auth.model.Role;

public class RouteAuthorizationCoverageTest extends SecurityIntegrationTestBase {

    private static final String EMAIL = "an@gmail.com";
    private static final String PUBLIC_PREFIX = "/api/v1/auth/";
    private static final String ADMIN_PREFIX = "/api/v1/admin/";
    private static final String USER_PREFIX = "/api/v1/user/";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private record Route(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @BeforeEach
    void seedUser() {
        createUser(EMAIL, PASSWORD, Role.USER, 1, 1);
    }

    private List<Route> discoverRoutes() {
        Set<Route> routes = new LinkedHashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;
            }
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            List<HttpMethod> methods = new ArrayList<>();
            if (declared.isEmpty()) {
                methods.add(HttpMethod.GET);
                methods.add(HttpMethod.POST);
            } else {
                declared.forEach(m -> methods.add(HttpMethod.valueOf(m.name())));
            }
            for (String pattern : patterns.getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "1").replaceAll("/{2,}", "/");
                for (HttpMethod method : methods) {
                    routes.add(new Route(method, path));
                }
            }
        }
        return new ArrayList<>(routes);
    }

    private List<Route> protectedRoutes() {
        return discoverRoutes().stream()
                .filter(r -> !r.path().startsWith(PUBLIC_PREFIX))
                .filter(r -> !r.path().equals("/error"))
                .toList();
    }

    private MvcResult send(Route route, String accessToken) throws Exception {
        var builder = request(route.method(), route.path())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return mockMvc.perform(builder).andReturn();
    }

    @Test
    @DisplayName("[Đối chứng] Việc quét route hoạt động: tìm thấy các route đã biết của user, admin và giao dịch")
    void discovery_findsKnownRoutes() {
        List<String> paths = protectedRoutes().stream().map(Route::path).toList();

        assertThat(paths).contains(
                USER_URL,
                ADMIN_URL,
                "/api/v1/user/open-wallet",
                "/api/v1/user/wallet-details",
                "/api/v1/user/transaction/create/top-up",
                "/api/v1/user/transaction/execute");
    }

    @Test
    @DisplayName("Mọi route ngoài /api/v1/auth/** đều trả 401 khi KHÔNG có token (mặc định chặn)")
    void everyNonAuthRoute_rejectsAnonymousRequests() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Route route : protectedRoutes()) {
            MvcResult result = send(route, null);
            if (statusOf(result) != 401) {
                violations.add(route + " => " + statusOf(result) + " (mong đợi 401)");
            } else if (body(result).contains("\"data\"")) {
                violations.add(route + " => 401 nhưng body có trường data");
            }
        }

        assertThat(violations).as("Các route đang bị bỏ ngỏ cho người chưa đăng nhập").isEmpty();
    }

    @Test
    @DisplayName("Mọi route /api/v1/admin/** trả 403 cho token của USER thường")
    void adminRoutes_areForbiddenForUserToken() throws Exception {
        Tokens user = loginOk(EMAIL, PASSWORD);
        List<Route> adminRoutes = protectedRoutes().stream()
                .filter(r -> r.path().startsWith(ADMIN_PREFIX))
                .toList();
        assertThat(adminRoutes).as("phải có ít nhất 1 route admin để kiểm tra").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Route route : adminRoutes) {
            int status = statusOf(send(route, user.access()));
            if (status != 403) {
                violations.add(route + " => " + status + " (mong đợi 403)");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Mọi route /api/v1/user/** KHÔNG bị chặn 401/403 với token USER hợp lệ (không chặn nhầm)")
    void userRoutes_areReachableWithUserToken() throws Exception {
        Tokens user = loginOk(EMAIL, PASSWORD);
        List<Route> userRoutes = protectedRoutes().stream()
                .filter(r -> r.path().startsWith(USER_PREFIX))
                .toList();
        assertThat(userRoutes).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Route route : userRoutes) {
            int status = statusOf(send(route, user.access()));
            if (status == 401 || status == 403) {
                violations.add(route + " => " + status + " (token USER hợp lệ bị chặn)");
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Route công khai chỉ nằm dưới /api/v1/auth/ và chỉ nhận POST (không lộ dữ liệu qua GET)")
    void publicRoutes_areOnlyPostUnderAuthPrefix() {
        List<Route> publicRoutes = discoverRoutes().stream()
                .filter(r -> r.path().startsWith(PUBLIC_PREFIX))
                .toList();

        assertThat(publicRoutes).isNotEmpty();
        assertThat(publicRoutes).allMatch(r -> r.method() == HttpMethod.POST,
                "route công khai không phải POST: " + publicRoutes);
    }
}
