package com.backend.backend.Auth.Service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.backend.backend.Auth.CustomException.InvalidRegisterRequest;
import com.backend.backend.Auth.CustomException.UserExistedException;
import com.backend.backend.Auth.Model.User;
import com.backend.backend.Auth.Repository.AuthRepository;
import com.backend.backend.DTO.LoginRequest;
import com.backend.backend.DTO.RegisterRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AuthRepository authRepository;
    private final EmailVerificationService emailVerificationService;

    public User login(LoginRequest request) {
        if (!ValidationUtils.isValidEmail(request.getEmail()))
            throw new InvalidRegisterRequest("Định dạng email không hợp lệ");
        authenticationManager
                .authenticate(new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        return authRepository
                .findUserByEmail(request.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy email: " + request.getEmail()));
    }

    public void register(RegisterRequest request) {
        if (!ValidationUtils.isValidUsername(request.getUsername()))
            throw new InvalidRegisterRequest("Username không hợp lệ! Username phải chứa từ 6 kí tự");

        if (!ValidationUtils.isValidEmail(request.getEmail()))
            throw new InvalidRegisterRequest("Định dạng email không hợp lệ");

        if (authRepository.findUserByEmail(request.getEmail()).orElse(null) != null)
            throw new UserExistedException("Email đã tồn tại");

        if (!ValidationUtils.isValidPassword(request.getPassword()))
            throw new InvalidRegisterRequest("Mật khẩu không hợp lệ! Mật khẩu phải chứa từ 12 kí tự");

        if (!ValidationUtils.isValidFullName(request.getFullName()))
            throw new InvalidRegisterRequest("Fullname không hợp lệ! Fullname phải chứa từ 6 kí tự");
        request.setPassword(passwordEncoder.encode(request.getPassword()));
        authRepository.register(request);
        emailVerificationService.generateAndSend(request.getEmail());
    }

    public void verifyEmail(String email, String code) {
        emailVerificationService.verify(email, code);
        authRepository.markVerified(email);
    }

    public void resendVerificationCode(String email) {
        User user = findUserByEmail(email);
        if (user.getIsVerified() != null && user.getIsVerified() >= 1) {
            throw new InvalidRegisterRequest("Email này đã được xác thực trước đó");
        }
        emailVerificationService.generateAndSend(email);
    }

    public User findUserByEmail(String email) {
        return authRepository
                .findUserByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy email: " + email));
    }
}
