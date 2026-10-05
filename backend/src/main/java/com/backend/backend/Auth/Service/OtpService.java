package com.backend.backend.Auth.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OTP_BOUND = 100_000_000; // 8 chữ số

    private final SecretKeySpec keySpec;

    public OtpService(@Value("${app.otp.secret}") String secret) {
        this.keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Sinh OTP 8 số, giữ cả số 0 ở đầu (vd "00123456"). */
    public String generate() {
        return String.format("%08d", RANDOM.nextInt(OTP_BOUND));
    }

    /** HMAC-SHA256 với secret của server. */
    public String hash(String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keySpec);
            return HexFormat.of().formatHex(mac.doFinal(otp.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** So sánh constant-time để tránh timing attack. */
    public boolean matches(String rawOtp, String storedHash) {
        if (rawOtp == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(rawOtp).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
