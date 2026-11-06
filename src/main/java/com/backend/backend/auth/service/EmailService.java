package com.backend.backend.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.backend.backend.transaction.Transaction;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${spring.mail.my-email}")
    private String fromAddress;

    public void sendVerificationCode(String to, String code) {
        send(to, "Mã xác thực cho tài khoản MiniEwallet",
                "Mã xác thực của bạn là: " + code + "\n"
                        + "Không chia sẻ mã này cho bất kỳ ai.");
    }

    public void sendPasswordResetOtp(String to, String otp, int ttlMinutes) {
        send(to, "Mã xác thực đặt lại mật khẩu",
                "Mã OTP của bạn là: " + otp + "\n"
                        + "Mã có hiệu lực trong " + ttlMinutes + " phút. Không chia sẻ mã này cho bất kỳ ai.");
    }

    public void sendPasswordChangedNotice(String to) {
        send(to, "Mật khẩu đã được thay đổi",
                "Mật khẩu tài khoản của bạn vừa được thay đổi. "
                        + "Nếu không phải bạn thực hiện, hãy liên hệ hỗ trợ ngay.");
    }

    public void sendTransactionOtp(String to, String otp, int ttlMinutes, Transaction transaction) {
        send(to, "Mã xác thực cho giao dịch " + transaction.getReferenceId(),
                "Mã OTP của bạn là: " + otp + "\n"
                        + "Mã có hiệu lực trong " + ttlMinutes + " phút. Không chia sẻ mã này cho bất kỳ ai.");
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        mailSender.send(msg);
    }
}
