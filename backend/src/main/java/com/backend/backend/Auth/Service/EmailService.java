package com.backend.backend.Auth.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public void sendVerificationCode(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Mã xác thực tài khoản Mini E-Wallet");
        message.setText("Mã xác thực của bạn là: " + code +
                "\nMã có hiệu lực trong 10 phút. Không chia sẻ mã này với bất kỳ ai.");
        mailSender.send(message);
    }
}
