package com.backend.backend.global;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.auth.customexception.authentication.AddUserError;
import com.backend.backend.auth.customexception.authentication.InvalidRegisterRequest;
import com.backend.backend.auth.customexception.authentication.LoginLockedException;
import com.backend.backend.auth.customexception.authentication.UnloginException;
import com.backend.backend.auth.customexception.authentication.UserExistedException;
import com.backend.backend.auth.customexception.email.ExpiredVerificationCodeException;
import com.backend.backend.auth.customexception.email.InvalidVerificationCodeException;
import com.backend.backend.auth.customexception.email.InvalidVerifyRequestException;
import com.backend.backend.auth.customexception.email.TooManyResendAttemptsException;
import com.backend.backend.auth.customexception.email.TooManyVerificationAttemptsException;
import com.backend.backend.auth.customexception.otp.InvalidOtpException;
import com.backend.backend.auth.customexception.otp.InvalidResetTokenException;
import com.backend.backend.auth.customexception.otp.ManyOtpException;
import com.backend.backend.auth.customexception.token.ExpiredRefreshTokenException;
import com.backend.backend.auth.customexception.token.InvalidRefreshToken;
import com.backend.backend.auth.customexception.token.RefreshTokenIsUsedException;
import com.backend.backend.auth.customexception.token.RevokingException;
import com.backend.backend.auth.filter.JwtAuthenticationFilter;
import com.backend.backend.auth.service.MyUserDetailsService;
import com.backend.backend.transaction.customexception.DuplicateTransactionException;
import com.backend.backend.transaction.customexception.ExceedPendingException;
import com.backend.backend.transaction.customexception.InvalidTransactionException;
import com.backend.backend.transaction.customexception.MailSendFailedException;
import com.backend.backend.transaction.customexception.NotEnoughAmountException;
import com.backend.backend.transaction.customexception.NotUserTransaction;
import com.backend.backend.transaction.customexception.TransactionError;
import com.backend.backend.transaction.customexception.TransactionNotFound;
import com.backend.backend.transaction.customexception.TransactionRecordError;
import com.backend.backend.wallet.customexception.WalletNotFoundException;
import com.backend.backend.wallet.customexception.WalletOpenedException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    private static final String MESSAGE = "Thông báo lỗi tùy chỉnh";
    private static final String GENERIC_500 = "Đã có lỗi xảy ra, vui lòng thử lại sau";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private MyUserDetailsService myUserDetailsService;

    // Controller giả: chỉ để tạo ra lỗi cho handler bắt
    @RestController
    public static class ThrowingController {

        static Exception exceptionToThrow;

        @GetMapping("/test/throw")
        public String throwException() throws Exception {
            throw exceptionToThrow;
        }

        @PostMapping("/test/body")
        public String body(@RequestBody AmountRequest request) {
            return "ok";
        }

        @PostMapping("/test/valid")
        public String valid(@Valid @RequestBody NameRequest request) {
            return "ok";
        }

        @GetMapping("/test/number/{id}")
        public String number(@PathVariable("id") int id) {
            return "ok";
        }

        @GetMapping("/test/param")
        public String param(@RequestParam("name") String name) {
            return "ok";
        }
    }

    public record AmountRequest(BigDecimal amount) {
    }

    public record NameRequest(@NotBlank(message = "Tên không được để trống") String name) {
    }

    private ResultActions performThrow(Exception ex) throws Exception {
        ThrowingController.exceptionToThrow = ex;
        return mockMvc.perform(get("/test/throw")).andDo(print());
    }

    // =====================================================================
    // PHẦN 1: EXCEPTION TỰ ĐỊNH NGHĨA -> đúng status, trả đúng message
    // (giả định mọi exception đều có constructor nhận 1 String; class nào khác thì
    // sửa dòng đó)
    // =====================================================================

    static Stream<Arguments> customExceptions() {
        return Stream.of(
                // 400
                Arguments.of(new InvalidRegisterRequest(MESSAGE), 400),
                Arguments.of(new InvalidVerificationCodeException(MESSAGE), 400),
                Arguments.of(new ExpiredVerificationCodeException(MESSAGE), 400),
                Arguments.of(new InvalidVerifyRequestException(MESSAGE), 400),
                Arguments.of(new InvalidOtpException(MESSAGE), 400),
                Arguments.of(new InvalidResetTokenException(MESSAGE), 400),
                Arguments.of(new InvalidTransactionException(MESSAGE), 400),
                Arguments.of(new NotEnoughAmountException(MESSAGE), 400),
                // 403
                Arguments.of(new NotUserTransaction(MESSAGE), 403),
                // 404 (UsernameNotFoundException là con của AuthenticationException,
                // phải ra 404 chứ không phải 401)
                Arguments.of(new UsernameNotFoundException(MESSAGE), 404),
                Arguments.of(new TransactionNotFound(MESSAGE), 404),
                Arguments.of(new WalletNotFoundException(MESSAGE), 404),
                // 409
                Arguments.of(new UserExistedException(MESSAGE), 409),
                Arguments.of(new WalletOpenedException(MESSAGE), 409),
                Arguments.of(new DuplicateTransactionException(MESSAGE), 409),
                Arguments.of(new TransactionError(MESSAGE), 409),
                // 429
                Arguments.of(new TooManyVerificationAttemptsException(MESSAGE), 429),
                Arguments.of(new TooManyResendAttemptsException(MESSAGE), 429),
                Arguments.of(new LoginLockedException(MESSAGE), 429),
                Arguments.of(new ManyOtpException(MESSAGE), 429),
                Arguments.of(new ExceedPendingException(MESSAGE), 429),
                // 500
                Arguments.of(new AddUserError(MESSAGE), 500),
                Arguments.of(new TransactionRecordError(MESSAGE), 500),
                // 502
                Arguments.of(new MailSendFailedException(MESSAGE), 502));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("customExceptions")
    void customException_returnsMappedStatusAndOwnMessage(Exception exception, int expectedStatus)
            throws Exception {
        performThrow(exception)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.error").value(HttpStatus.valueOf(expectedStatus).getReasonPhrase()))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andExpect(jsonPath("$.path").value("/test/throw"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // =====================================================================
    // 401: AuthenticationException -> message cố định theo loại, không dùng message
    // gốc
    // =====================================================================

    static Stream<Arguments> authenticationExceptions() {
        return Stream.of(
                Arguments.of(new BadCredentialsException("x"), "Email hoặc mật khẩu không chính xác"),
                Arguments.of(new DisabledException("x"), "Tài khoản của bạn chưa được xác thực email"),
                Arguments.of(new LockedException("x"), "Tài khoản của bạn đã bị khóa"),
                Arguments.of(new UnloginException("x"),
                        "Người dùng chưa đăng nhập hoặc Refresh Token không hợp lệ"),
                Arguments.of(new ExpiredRefreshTokenException("x"),
                        "Refresh Token đã hết hạn, vui lòng đăng nhập lại"),
                Arguments.of(new InvalidRefreshToken("x"), "Refresh Token không hợp lệ"),
                Arguments.of(new RevokingException("x"), "RefreshToken đang thu hồi bởi một request khác"),
                Arguments.of(new RefreshTokenIsUsedException("x"),
                        "RefreshToken bị phát hiện là sử dụng lại, đang thực hiện đăng xuất toàn bộ..."),
                // nhánh mặc định
                Arguments.of(new InsufficientAuthenticationException("x"), "Xác thực thất bại"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticationExceptions")
    void authenticationException_returns401WithFixedMessage(Exception exception, String expectedMessage)
            throws Exception {
        performThrow(exception)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(expectedMessage));
    }

    // =====================================================================
    // PHẦN 2: EXCEPTION CỦA SPRING -> đúng status, message cố định
    // =====================================================================

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/test/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{amount: "))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu gửi lên không hợp lệ"));
    }

    @Test
    void emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu gửi lên không hợp lệ"));
    }

    @Test
    void amountIsText_returns400() throws Exception {
        mockMvc.perform(post("/test/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"abc\"}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Dữ liệu gửi lên không hợp lệ"));
    }

    @Test
    void validationFailed_returns400WithFieldMessage() throws Exception {
        mockMvc.perform(post("/test/valid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name: Tên không được để trống"));
    }

    @Test
    void pathVariableWrongType_returns400() throws Exception {
        mockMvc.perform(get("/test/number/abc"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tham số 'id' không đúng định dạng"));
    }

    @Test
    void missingRequiredParam_returns400() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Yêu cầu thiếu thông tin bắt buộc"));
    }

    @Test
    void accessDenied_returns403AndHidesDetails() throws Exception {
        performThrow(new AccessDeniedException("secret detail"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này"))
                .andExpect(content().string(not(containsString("secret detail"))));
    }

    @Test
    void unknownUrl_returns404() throws Exception {
        mockMvc.perform(get("/test/khong-ton-tai"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Đường dẫn không tồn tại"));
    }

    @Test
    void wrongHttpMethod_returns405() throws Exception {
        mockMvc.perform(post("/test/throw"))
                .andDo(print())
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("Phương thức không được hỗ trợ"));
    }

    @Test
    void wrongContentType_returns415() throws Exception {
        mockMvc.perform(post("/test/body")
                .contentType(MediaType.TEXT_PLAIN)
                .content("amount=1000"))
                .andDo(print())
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Kiểu dữ liệu không được hỗ trợ"));
    }

    // =====================================================================
    // PHẦN 3: LỖI BẤT NGỜ -> 500, message chung, không lộ chi tiết
    // =====================================================================

    static Stream<Arguments> unexpectedExceptions() {
        return Stream.of(
                Arguments.of(new RuntimeException("could not execute statement; table wallet_tx"), "wallet_tx"),
                Arguments.of(new DataIntegrityViolationException(
                        "Duplicate entry 'an@gmail.com' for key 'users.email'"), "users.email"),
                Arguments.of(new IllegalStateException("jdbc:mysql://localhost:3306/secret_db"), "secret_db"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unexpectedExceptions")
    void unexpectedException_returns500WithGenericMessage(Exception exception, String secret)
            throws Exception {
        performThrow(exception)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(GENERIC_500))
                .andExpect(content().string(not(containsString(secret))));
    }
}
