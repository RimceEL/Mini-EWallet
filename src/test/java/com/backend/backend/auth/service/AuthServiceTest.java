package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.backend.backend.auth.customexception.authentication.InvalidRegisterRequest;
import com.backend.backend.auth.customexception.authentication.LoginLockedException;
import com.backend.backend.auth.customexception.authentication.UserExistedException;
import com.backend.backend.auth.customexception.email.InvalidVerificationCodeException;
import com.backend.backend.auth.customexception.email.InvalidVerifyRequestException;
import com.backend.backend.auth.dto.LoginRequest;
import com.backend.backend.auth.dto.RegisterRequest;
import com.backend.backend.auth.model.Role;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.repository.AuthRepository;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    private static final String EMAIL = "an@gmail.com";
    private static final String PASSWORD = "MatKhauDuMuoiHaiKyTu!1";

    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private AuthRepository authRepository;
    @Mock
    private EmailVerificationService emailVerificationService;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private AsyncMailRunner asyncMailRunner;

    @InjectMocks
    private AuthService authService;

    private static User validUser() {
        return User.builder()
                .id("u-1")
                .email(EMAIL)
                .username("nguoidung01")
                .password("$2a$12$hash")
                .role(Role.USER)
                .isActive(1)
                .isVerified(1)
                .build();
    }

    private static RegisterRequest validRegisterRequest() {
        return RegisterRequest.builder()
                .email(EMAIL)
                .username("nguoidung01")
                .password(PASSWORD)
                .fullName("Nguyen Van An")
                .build();
    }

    // ======================= login =======================

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "not-an-email", "a@b..com" })
    @DisplayName("login: email sai định dạng => ném InvalidRegisterRequest, không đụng tới LoginAttemptService hay AuthenticationManager")
    void login_invalidEmail_throwsAndTouchesNothing(String email) {
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, PASSWORD)))
                .isInstanceOf(InvalidRegisterRequest.class);

        verifyNoInteractions(loginAttemptService, authenticationManager);
    }

    @Test
    @DisplayName("login: loginAttemptFailed trả true => ném LoginLockedException kèm số giây còn lại và không authenticate")
    void login_whenLockedByAttemptService_throwsLoginLockedWithoutAuthenticating() {
        when(loginAttemptService.loginAttemptFailed(EMAIL)).thenReturn(true);
        when(loginAttemptService.getRemainingLockSeconds(EMAIL)).thenReturn(42L);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(LoginLockedException.class)
                .hasMessageContaining("42");

        verifyNoInteractions(authenticationManager);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
    }

    @Test
    @DisplayName("login: sai mật khẩu / chưa xác thực / bị khoá => lan truyền exception và KHÔNG reset bộ đếm")
    void login_whenAuthenticationFails_doesNotCallLoginSucceeded() {
        when(loginAttemptService.loginAttemptFailed(EMAIL)).thenReturn(false);

        doThrow(new BadCredentialsException("bad")).when(authenticationManager).authenticate(any());
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);

        doThrow(new DisabledException("disabled")).when(authenticationManager).authenticate(any());
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(DisabledException.class);

        doThrow(new LockedException("locked")).when(authenticationManager).authenticate(any());
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(LockedException.class);

        verify(loginAttemptService, never()).loginSucceeded(anyString());
    }

    @Test
    @DisplayName("login: thành công => xác thực bằng email/password, gọi loginSucceeded và trả về principal User")
    void login_success_resetsAttemptsAndReturnsUser() {
        User expected = validUser();
        Authentication auth = new UsernamePasswordAuthenticationToken(expected, null, expected.getAuthorities());
        when(loginAttemptService.loginAttemptFailed(EMAIL)).thenReturn(false);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        User actual = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor = ArgumentCaptor
                .forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(tokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getPrincipal()).isEqualTo(EMAIL);
        assertThat(tokenCaptor.getValue().getCredentials()).isEqualTo(PASSWORD);
        verify(loginAttemptService).loginSucceeded(EMAIL);
    }

    // ======================= register =======================

    @Test
    @DisplayName("register: các trường không hợp lệ => ném InvalidRegisterRequest, không encode mật khẩu, không ghi DB, không gửi mail")
    void register_invalidFields_throwAndTouchNothing() {
        RegisterRequest badUser = validRegisterRequest();
        badUser.setUsername("abc");
        assertThatThrownBy(() -> authService.register(badUser)).isInstanceOf(InvalidRegisterRequest.class);

        RegisterRequest badEmail = validRegisterRequest();
        badEmail.setEmail("bad");
        assertThatThrownBy(() -> authService.register(badEmail)).isInstanceOf(InvalidRegisterRequest.class);

        RegisterRequest badPass = validRegisterRequest();
        badPass.setPassword("weak");
        assertThatThrownBy(() -> authService.register(badPass)).isInstanceOf(InvalidRegisterRequest.class);

        RegisterRequest badName = validRegisterRequest();
        badName.setFullName("An");
        assertThatThrownBy(() -> authService.register(badName)).isInstanceOf(InvalidRegisterRequest.class);

        verifyNoInteractions(passwordEncoder, authRepository, asyncMailRunner, emailVerificationService);
    }

    @Test
    @DisplayName("register: hợp lệ => mã hoá mật khẩu trước khi insert, rồi gửi mail xác thực bất đồng bộ")
    void register_valid_encodesSavesAndSchedulesVerificationMail() {
        RegisterRequest req = validRegisterRequest();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$12$encoded");

        authService.register(req);

        assertThat(req.getPassword()).isEqualTo("$2a$12$encoded");
        InOrder order = inOrder(passwordEncoder, authRepository, asyncMailRunner);
        order.verify(passwordEncoder).encode(PASSWORD);
        order.verify(authRepository).register(req);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        order.verify(asyncMailRunner).run(taskCaptor.capture());
        taskCaptor.getValue().run();
        verify(emailVerificationService).generateAndSend(EMAIL);
    }

    @Test
    @DisplayName("register: trùng email (DuplicateKeyException) => ném UserExistedException, không gửi mail")
    void register_duplicateEmail_throwsUserExisted() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$12$encoded");
        doThrow(new DuplicateKeyException("dup")).when(authRepository).register(any());

        assertThatThrownBy(() -> authService.register(validRegisterRequest()))
                .isInstanceOf(UserExistedException.class);

        verifyNoInteractions(asyncMailRunner, emailVerificationService);
    }

    @Test
    @DisplayName("register: lỗi DB khác (DataAccessResourceFailureException) => lan truyền nguyên bản, không nuốt thành UserExistedException")
    void register_unexpectedDbFailure_propagatesWithoutMail() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn("$2a$12$encoded");
        doThrow(new DataAccessResourceFailureException("db down")).when(authRepository).register(any());

        assertThatThrownBy(() -> authService.register(validRegisterRequest()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(asyncMailRunner, emailVerificationService);
    }

    // ======================= verifyEmail & resend =======================

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "invalid-email" })
    @DisplayName("verifyEmail: email rỗng/sai định dạng => ném InvalidVerifyRequestException, không gọi service hay DB")
    void verifyEmail_invalidEmail_throwsAndTouchesNothing(String email) {
        assertThatThrownBy(() -> authService.verifyEmail(email, "123456"))
                .isInstanceOf(InvalidVerifyRequestException.class);

        verifyNoInteractions(emailVerificationService, authRepository);
    }

    @Test
    @DisplayName("verifyEmail: verify thất bại => không gọi markVerified")
    void verifyEmail_whenVerificationFails_doesNotMarkVerified() {
        doThrow(new InvalidVerificationCodeException("wrong")).when(emailVerificationService).verify(EMAIL, "000000");

        assertThatThrownBy(() -> authService.verifyEmail(EMAIL, "000000"))
                .isInstanceOf(InvalidVerificationCodeException.class);

        verify(authRepository, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("verifyEmail: thành công => verify trước rồi mới markVerified")
    void verifyEmail_success_verifiesThenMarksVerified() {
        authService.verifyEmail(EMAIL, "123456");

        InOrder order = inOrder(emailVerificationService, authRepository);
        order.verify(emailVerificationService).verify(EMAIL, "123456");
        order.verify(authRepository).markVerified(EMAIL);
    }

    @Test
    @DisplayName("resendVerificationCode: user không tồn tại, đã verify hoặc bị khoá => ném InvalidVerifyRequestException")
    void resendVerificationCode_nonEligibleUser_throws() {
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.resendVerificationCode(EMAIL))
                .isInstanceOf(InvalidVerifyRequestException.class);

        User verified = validUser();
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.of(verified));
        assertThatThrownBy(() -> authService.resendVerificationCode(EMAIL))
                .isInstanceOf(InvalidVerifyRequestException.class);

        User locked = User.builder().email(EMAIL).isActive(0).isVerified(0).build();
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.of(locked));
        assertThatThrownBy(() -> authService.resendVerificationCode(EMAIL))
                .isInstanceOf(InvalidVerifyRequestException.class);

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("resendVerificationCode: hợp lệ (kể cả khi isVerified = null và isActive = 1) => gửi lại mã")
    void resendVerificationCode_eligibleUser_generatesAndSends() {
        User unverifiedWithNullFlag = User.builder().email(EMAIL).isActive(1).isVerified(null).build();
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.of(unverifiedWithNullFlag));

        authService.resendVerificationCode(EMAIL);

        verify(emailVerificationService).generateAndSend(EMAIL);
    }

    // ======================= findUserByEmail =======================

    @Test
    @DisplayName("findUserByEmail: tìm thấy trả về User, không thấy ném UsernameNotFoundException")
    void findUserByEmail_foundAndMissing() {
        User user = validUser();
        when(authRepository.findUserByEmail(EMAIL)).thenReturn(Optional.of(user));
        assertThat(authService.findUserByEmail(EMAIL)).isSameAs(user);

        when(authRepository.findUserByEmail("missing@gmail.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.findUserByEmail("missing@gmail.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("missing@gmail.com");
    }
}
