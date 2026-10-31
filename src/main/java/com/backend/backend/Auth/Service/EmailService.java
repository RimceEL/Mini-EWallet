package com.backend.backend.Auth.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.backend.backend.Transaction.Transaction;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${spring.mail.my-email}")
    private String fromAddress;

    @Async
    public void sendVerificationCode(String toEmail, String code) {
        try {

            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Mã xác thực tài khoản Mini E-Wallet");
            String formattedCode = String.join("", code.split(""));

            String htmlContent = "<div style='background-color: #f2f3f5; padding: 40px 0; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;'>"
                    +
                    "<div style='max-width: 600px; margin: 0 auto;'>" +

                    // Logo Header (Mô phỏng vị trí logo OpenTable, bạn có thể thay bằng thẻ <img>
                    // chứa logo ví của bạn)
                    "<div style='text-align: center; margin-bottom: 30px;'>" +
                    "<h1 style='color: #da3e44; margin: 0; font-size: 24px; font-weight: bold;'>● Mini E-Wallet</h1>" +
                    "</div>" +

                    // Hộp nội dung chính màu trắng
                    "<div style='background-color: #ffffff; padding: 50px 40px; border-radius: 4px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); text-align: center; color: #4a4a4a;'>"
                    +

                    // Tiêu đề
                    "<h2 style='color: #4a7a8c; font-size: 28px; font-weight: 400; margin-top: 0; margin-bottom: 30px;'>"
                    +
                    "Confirm it's you" +
                    "</h2>" +

                    // Hướng dẫn nhập mã thủ công
                    "<p style='font-size: 16px; margin-bottom: 30px; color: #666666;'>" +
                    "Here is your verify code for Mini E-Wallet. Don't send this code to anyone and it will be expired in 10 minutes"
                    +
                    "</p>" +

                    // Mã OTP với khoảng cách lớn
                    "<div style='font-size: 24px; font-weight: 400; letter-spacing: 4px; margin-bottom: 50px; color: #222222;'>"
                    +
                    formattedCode +
                    "</div>" +

                    // Ghi chú bỏ qua
                    "<p style='font-size: 14px; color: #888888; margin-bottom: 0;'>" +
                    "If you didn't request to have your identity confirmed, ignore this email." +
                    "</p>" +

                    "</div>" +
                    "</div>" +
                    "</div>";

            helper.setText(htmlContent, true);

            mailSender.send(message);

        } catch (MessagingException e) {
            System.err.println("Lỗi khi gửi email xác thực định dạng HTML: " + e.getMessage());
        }
    }

    @Async
    public void sendPasswordResetOtp(String to, String otp, int ttlMinutes) {
        send(to, "Mã xác thực đặt lại mật khẩu",
                "Mã OTP của bạn là: " + otp + "\n"
                        + "Mã có hiệu lực trong " + ttlMinutes + " phút. Không chia sẻ mã này cho bất kỳ ai.");
    }

    @Async
    public void sendPasswordChangedNotice(String to) {
        send(to, "Mật khẩu đã được thay đổi",
                "Mật khẩu tài khoản của bạn vừa được thay đổi. "
                        + "Nếu không phải bạn thực hiện, hãy liên hệ hỗ trợ ngay.");
    }

    @Async
    public void sendTransactionOtp(String to, String otp, int ttlMinutes, Transaction transaction) {
        send(to, "Mã xác thực cho giao dịch " + transaction.getReferenceId(),
                "Mã OTP của bạn là: " + otp + "\n"
                        + "Mã có hiệu lực trong " + ttlMinutes + " phút. Không chia sẻ mã này cho bất kỳ ai.");
    }

    private void send(String to, String subject, String text) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(text);
            mailSender.send(msg);
        } catch (MailException e) {
            System.err.println("Lỗi khi gửi email xác thực: " + e.getMessage());
            throw e;
        }
    }
}
