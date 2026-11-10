package com.backend.backend.global;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
import com.backend.backend.dto.ErrorResponse;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(NotUserTransaction.class)
    public ResponseEntity<ErrorResponse> handleForbidden(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    @ExceptionHandler({ UsernameNotFoundException.class, TransactionNotFound.class, WalletNotFoundException.class })
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({
            UserExistedException.class,
            WalletOpenedException.class,
            DuplicateTransactionException.class,
            TransactionError.class })
    public ResponseEntity<ErrorResponse> handleConflict(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler({
            TooManyVerificationAttemptsException.class,
            TooManyResendAttemptsException.class,
            LoginLockedException.class,
            ManyOtpException.class,
            ExceedPendingException.class })
    public ResponseEntity<ErrorResponse> handleTooManyRequests(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    @ExceptionHandler({ AddUserError.class, TransactionRecordError.class })
    public ResponseEntity<ErrorResponse> handleServerError(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    @ExceptionHandler(MailSendFailedException.class)
    public ResponseEntity<ErrorResponse> handleBadGateway(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isEmpty()) {
            message = "Dữ liệu gửi lên không hợp lệ";
        }
        return buildError(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ", request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST,
                "Tham số '" + ex.getName() + "' không đúng định dạng", request);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestValue(ServletRequestBindingException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "Yêu cầu thiếu thông tin bắt buộc", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này", request);
    }

    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ErrorResponse> handleNoResource(Exception ex, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "Đường dẫn không tồn tại", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức không được hỗ trợ", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Kiểu dữ liệu không được hỗ trợ", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Lỗi không mong đợi tại {} {}", request.getMethod(), request.getRequestURI(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra, vui lòng thử lại sau", request);
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