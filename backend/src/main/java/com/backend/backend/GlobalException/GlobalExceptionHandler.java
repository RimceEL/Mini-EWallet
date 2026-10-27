package com.backend.backend.GlobalException;

import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.backend.backend.Auth.CustomException.Authentication.AddUserError;
import com.backend.backend.Auth.CustomException.Authentication.InvalidRegisterRequest;
import com.backend.backend.Auth.CustomException.Authentication.LoginLockedException;
import com.backend.backend.Auth.CustomException.Authentication.UnloginException;
import com.backend.backend.Auth.CustomException.Authentication.UserExistedException;
import com.backend.backend.Auth.CustomException.Email.ExpiredVerificationCodeException;
import com.backend.backend.Auth.CustomException.Email.InvalidVerificationCodeException;
import com.backend.backend.Auth.CustomException.Email.InvalidVerifyRequestException;
import com.backend.backend.Auth.CustomException.Email.TooManyResendAttemptsException;
import com.backend.backend.Auth.CustomException.Email.TooManyVerificationAttemptsException;
import com.backend.backend.Auth.CustomException.Otp.InvalidOtpException;
import com.backend.backend.Auth.CustomException.Otp.InvalidResetTokenException;
import com.backend.backend.Auth.CustomException.Otp.ManyOtpException;
import com.backend.backend.Auth.CustomException.Token.ExpiredRefreshTokenException;
import com.backend.backend.Auth.CustomException.Token.InvalidRefreshToken;
import com.backend.backend.Auth.CustomException.Token.RefreshTokenIsUsedException;
import com.backend.backend.Auth.CustomException.Token.RevokingException;
import com.backend.backend.DTO.ErrorResponse;
import com.backend.backend.Transaction.CustomException.DuplicateTransactionException;
import com.backend.backend.Transaction.CustomException.InvalidTransactionException;
import com.backend.backend.Transaction.CustomException.MailSendFailedException;
import com.backend.backend.Transaction.CustomException.NotEnoughAmountException;
import com.backend.backend.Transaction.CustomException.NotUserTransaction;
import com.backend.backend.Transaction.CustomException.TransactionError;
import com.backend.backend.Transaction.CustomException.TransactionNotFound;
import com.backend.backend.Transaction.CustomException.TransactionRecordError;
import com.backend.backend.Wallet.CustomException.WalletOpenedException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    // 403
    @ExceptionHandler(NotUserTransaction.class)
    public ResponseEntity<ErrorResponse> handleForbidden(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    // 404
    @ExceptionHandler({ UsernameNotFoundException.class, TransactionNotFound.class })
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    // 409
    @ExceptionHandler({
            UserExistedException.class,
            WalletOpenedException.class,
            DuplicateTransactionException.class,
            TransactionError.class })
    public ResponseEntity<ErrorResponse> handleConflict(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // 500
    @ExceptionHandler({ AddUserError.class, TransactionRecordError.class })
    public ResponseEntity<ErrorResponse> handleServerError(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    // 502
    @ExceptionHandler(MailSendFailedException.class)
    public ResponseEntity<ErrorResponse> handleBadGateway(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    // 400
    @ExceptionHandler({
            InvalidRegisterRequest.class,
            InvalidVerificationCodeException.class,
            ExpiredVerificationCodeException.class,
            InvalidVerifyRequestException.class,
            InvalidOtpException.class,
            InvalidResetTokenException.class,
            InvalidTransactionException.class,
            NotEnoughAmountException.class })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // 429
    @ExceptionHandler({
            TooManyVerificationAttemptsException.class,
            TooManyResendAttemptsException.class,
            LoginLockedException.class,
            ManyOtpException.class })
    public ResponseEntity<ErrorResponse> handleTooManyRequests(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    // 401
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
            HttpServletRequest request) {
        String message = "Xác thực thất bại";

        if (ex instanceof BadCredentialsException) {
            message = "Email hoặc mật khẩu không chính xác";
        } else if (ex instanceof DisabledException) {
            message = "Tài khoản của bạn chưa được xác thực email";
        } else if (ex instanceof LockedException) {
            message = "Tài khoản của bạn đã bị khóa";
        } else if (ex instanceof UnloginException) {
            message = "Người dùng chưa đăng nhập hoặc Refresh Token không hợp lệ";
        } else if (ex instanceof ExpiredRefreshTokenException) {
            message = "Refresh Token đã hết hạn, vui lòng đăng nhập lại";
        } else if (ex instanceof InvalidRefreshToken) {
            message = "Refresh Token không hợp lệ";
        } else if (ex instanceof RevokingException) {
            message = "RefreshToken đang thu hồi bởi một request khác";
        } else if (ex instanceof RefreshTokenIsUsedException) {
            message = "RefreshToken bị phát hiện là sử dụng lại, đang thực hiện đăng xuất toàn bộ...";
        }

        return buildError(HttpStatus.UNAUTHORIZED, message, request);
    }

    private ResponseEntity<ErrorResponse> buildError(HttpStatus status, String message,
            HttpServletRequest request) {
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();
        return new ResponseEntity<>(errorResponse, status);
    }
}