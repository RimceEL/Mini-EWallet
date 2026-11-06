package com.backend.backend.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.backend.backend.auth.customexception.email.TooManyResendAttemptsException;
import com.backend.backend.auth.customexception.otp.InvalidOtpException;
import com.backend.backend.auth.customexception.otp.InvalidResetTokenException;
import com.backend.backend.auth.customexception.otp.ManyOtpException;
import com.backend.backend.auth.service.AsyncMailRunner;
import com.backend.backend.auth.service.EmailService;
import com.backend.backend.auth.service.OtpService;
import com.backend.backend.custom.RateLimiter;
import com.backend.backend.transaction.customexception.InvalidTransactionException;
import com.backend.backend.transaction.customexception.NotUserTransaction;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OtpTransactionService {
    private static final String OTP_PREFIX = "transaction:otp:";
    private static final String TOKEN_PREFIX = "transaction:token:";
    private static final String OTP_FAIL_PREFIX = "transaction:otp-fail:";
    private static final String RESEND_PREFIX = "transaction:resend:";
    private static final String REFUND_PREFIX = "transaction:resend-refund:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final OtpService otpService;
    private final EmailService emailService;
    private final RateLimiter rateLimiter;
    private final TransactionService transactionService;
    private final AsyncMailRunner asyncMailRunner;

    private static final String OTP_INVALID_MSG = "Otp đã hết hạn hoặc không hợp lệ";
    private static final String TOKEN_INVALID_MSG = "Token đã hết hạn hoặc không hợp lệ";

    @Value("${app.transaction.otp-ttl-minutes:10}")
    private int otpTtlMinutes;
    @Value("${app.transaction.max-otp-attempts:5}")
    private int maxOtpAttempts;
    @Value("${app.transaction.token-ttl-minutes:10}")
    private int tokenTtlMinutes;
    @Value("${app.transaction.resend.max-attempts:5}")
    private int maxResendAttempts;
    @Value("${app.transaction.resend.lock-hours:24}")
    private long resendLockHours;

    public String transactionOtpKey(String referenceId, String email) {
        return OTP_PREFIX + referenceId + ":" + email;
    }

    public String transactionTokenKey(String referenceId, String email) {
        return TOKEN_PREFIX + referenceId + ":" + email;
    }

    private String otpFailKey(String referenceId, String email) {
        return OTP_FAIL_PREFIX + referenceId + ":" + email;
    }

    private String resendKey(String referenceId, String email) {
        return RESEND_PREFIX + referenceId + ":" + email;
    }

    private String refundKey(String referenceId, String email) {
        return REFUND_PREFIX + referenceId + ":" + email;
    }

    public void transactionRequest(Transaction transaction, String email) {
        if (transaction == null)
            throw new InvalidTransactionException("Giao dịch phải được đính kèm");
        if (transaction.getStatus() != TransactionStatus.PENDING)
            throw new InvalidTransactionException(
                    "Giao dịch được yêu cầu xác thực không hợp lệ hoặc đã hết hạn thực hiện");
        if (!transactionService.isUserWallet(transaction.getSourceWalletId(), email))
            throw new NotUserTransaction("Phải đúng người thực hiện giao dịch xác nhận");

        Long attempts = rateLimiter.hit(resendKey(transaction.getReferenceId(), email), resendLockHours * 3600L,
                maxResendAttempts);

        if (attempts > maxResendAttempts)
            throw new TooManyResendAttemptsException(
                    "Bạn đã yêu cầu gửi otp cho giao dịch này quá nhiều lần, việc gửi otp cho giao dịch này đã bị vô hiệu hoá");
        String otp = otpService.generate();
        rateLimiter.setExAndDelete(
                transactionOtpKey(transaction.getReferenceId(), email),
                otpService.hash(otp),
                Duration.ofMinutes(otpTtlMinutes),
                otpFailKey(transaction.getReferenceId(), email));

        asyncMailRunner.run(() -> emailService.sendTransactionOtp(email, otp, otpTtlMinutes, transaction),
                e -> {
                    String refundKey = refundKey(transaction.getReferenceId(), email);
                    if (rateLimiter.hit(refundKey, resendLockHours * 3600L, 3) <= 3) { // tối đa 3 lần hoàn mỗi 24 giờ
                        rateLimiter.refund(resendKey(transaction.getReferenceId(), email));
                    }
                });
    }

    public String verifyTransactionOtp(Transaction transaction, String email, String rawOtp) {
        if (transaction == null)
            throw new InvalidTransactionException("Giao dịch phải được đính kèm");
        if (rawOtp == null || rawOtp.isBlank())
            throw new InvalidOtpException("Xác thực phải gửi kèm Otp");
        if (transaction.getStatus() != TransactionStatus.PENDING)
            throw new InvalidTransactionException(
                    "Giao dịch được yêu cầu xác thực không hợp lệ hoặc đã hết hạn thực hiện");
        if (!transactionService.isUserWallet(transaction.getSourceWalletId(), email))
            throw new NotUserTransaction("Phải đúng người thực hiện giao dịch xác nhận");

        String otpKey = transactionOtpKey(transaction.getReferenceId(), email);
        String storedHash = redis.opsForValue().get(otpKey);

        Long fails = rateLimiter.hit(otpFailKey(transaction.getReferenceId(), email), otpTtlMinutes * 60L * 2L,
                maxOtpAttempts);

        if (fails != null && fails > maxOtpAttempts) {
            redis.delete(otpKey);
            throw new ManyOtpException("Bạn đã nhập sai OTP quá nhiều lần, vui lòng yêu cầu cấp mã mới");
        }

        if (storedHash == null
                || !otpService.matches(rawOtp, storedHash)
                || !rateLimiter.compareAndDelete(otpKey, storedHash)) // Kiểm tra otp thật sự trong redis thêm lần nữa
                                                                      // vì có thể otp thật đã bị ghi đè do một
                                                                      // request send otp
            throw new InvalidOtpException(OTP_INVALID_MSG);

        String token = generateToken();
        rateLimiter.setExAndDelete(
                transactionTokenKey(transaction.getReferenceId(), email),
                sha256(token),
                Duration.ofMinutes(tokenTtlMinutes),
                otpFailKey(transaction.getReferenceId(), email));
        return token;
    }

    public void verifyTransaction(Transaction transaction, String email, String rawToken) {
        if (transaction == null)
            throw new InvalidTransactionException("Giao dịch phải được đính kèm");
        if (rawToken == null || rawToken.isBlank())
            throw new InvalidResetTokenException("Phải gửi kèm token xác thực");
        if (transaction.getStatus() != TransactionStatus.PENDING)
            throw new InvalidTransactionException(
                    "Giao dịch được yêu cầu xác thực không hợp lệ hoặc đã hết hạn thực hiện");
        if (!transactionService.isUserWallet(transaction.getSourceWalletId(), email))
            throw new NotUserTransaction("Phải đúng người thực hiện giao dịch xác nhận");

        String storedHash = redis.opsForValue().get(transactionTokenKey(transaction.getReferenceId(), email));
        if (storedHash == null
                || !MessageDigest.isEqual(
                        storedHash.getBytes(StandardCharsets.UTF_8),
                        sha256(rawToken).getBytes(StandardCharsets.UTF_8))
                || !rateLimiter.compareAndDelete(transactionTokenKey(transaction.getReferenceId(), email), storedHash))
            throw new InvalidResetTokenException(TOKEN_INVALID_MSG);

        transactionService.execute(transaction);
        try {
            redis.delete(List.of(
                    resendKey(transaction.getReferenceId(), email),
                    transactionOtpKey(transaction.getReferenceId(), email),
                    otpFailKey(transaction.getReferenceId(), email),
                    refundKey(transaction.getReferenceId(), email)));
        } catch (RuntimeException e) {
            System.err.println("Không dọn được key Redis của giao dịch " + transaction.getReferenceId());
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32]; // 256 bit
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
