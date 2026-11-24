package com.backend.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;

public class EmailServiceTest {

    private static final String FROM = "noreply@miniewallet.test";
    private static final String TO = "an@gmail.com";
    private static final String CODE = "123456";
    private static final String OTP = "87654321";
    private static final int TTL_MINUTES = 10;
    private static final String REFERENCE_ID = "REF-2026-0001";

    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", FROM);
    }

    private static Transaction transaction(String referenceId) {
        return Transaction.builder()
                .id("tx-id")
                .referenceId(referenceId)
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .sourceWalletId("wallet-a")
                .destinationWalletId("wallet-b")
                .amount(new BigDecimal("150000"))
                .build();
    }

    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        verifyNoMoreInteractions(mailSender);
        return captor.getValue();
    }

    static Stream<Arguments> everyMailType() {
        return Stream.of(
                Arguments.of("sendVerificationCode",
                        (Consumer<EmailService>) service -> service.sendVerificationCode(TO, CODE)),
                Arguments.of("sendPasswordResetOtp",
                        (Consumer<EmailService>) service -> service.sendPasswordResetOtp(TO, OTP, TTL_MINUTES)),
                Arguments.of("sendPasswordChangedNotice",
                        (Consumer<EmailService>) service -> service.sendPasswordChangedNotice(TO)),
                Arguments.of("sendTryToLoginWarning",
                        (Consumer<EmailService>) service -> service.sendTryToLoginWarning(TO)),
                Arguments.of("sendTransactionOtp",
                        (Consumer<EmailService>) service -> service.sendTransactionOtp(TO, OTP, TTL_MINUTES,
                                transaction(REFERENCE_ID))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMailType")
    @DisplayName("Mọi loại mail: gửi đúng 1 lần, from = địa chỉ cấu hình, to = đúng 1 người nhận, không cc/bcc/replyTo, có tiêu đề và nội dung")
    void everyMail_hasCorrectEnvelope(String name, Consumer<EmailService> action) {
        action.accept(emailService);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getTo()).containsExactly(TO);
        assertThat(message.getCc()).isNullOrEmpty();
        assertThat(message.getBcc()).isNullOrEmpty();
        assertThat(message.getReplyTo()).isNull();
        assertThat(message.getSubject()).isNotBlank().doesNotContain("null");
        assertThat(message.getText()).isNotBlank().doesNotContain("null");
    }

    @Test
    @DisplayName("sendVerificationCode: tiêu đề nói về xác thực tài khoản, nội dung có mã và lời nhắc không chia sẻ")
    void sendVerificationCode_content() {
        emailService.sendVerificationCode(TO, CODE);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Mã xác thực cho tài khoản MiniEwallet");
        assertThat(message.getText()).contains(CODE).contains("Không chia sẻ");
    }

    @Test
    @DisplayName("sendPasswordResetOtp: nội dung có OTP và thời hạn (phút), tiêu đề nói về đặt lại mật khẩu")
    void sendPasswordResetOtp_content() {
        emailService.sendPasswordResetOtp(TO, OTP, 7);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Mã xác thực đặt lại mật khẩu");
        assertThat(message.getText()).contains(OTP).contains("7 phút").contains("Không chia sẻ");
    }

    @Test
    @DisplayName("sendPasswordChangedNotice: thông báo đổi mật khẩu, không chứa mã hay mật khẩu nào")
    void sendPasswordChangedNotice_content() {
        emailService.sendPasswordChangedNotice(TO);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Mật khẩu đã được thay đổi");
        assertThat(message.getText()).contains("vừa được thay đổi").contains("liên hệ hỗ trợ");
        assertThat(message.getText()).doesNotContainPattern("\\d{4,}");
    }

    @Test
    @DisplayName("sendTryToLoginWarning: cảnh báo khoá tài khoản và khuyên đổi mật khẩu, không chứa mã nào")
    void sendTryToLoginWarning_content() {
        emailService.sendTryToLoginWarning(TO);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Ai đó đang cố gắng truy cập vào tài khoản của bạn");
        assertThat(message.getText()).contains("tạm thời khoá").contains("đổi mật khẩu");
        assertThat(message.getText()).doesNotContainPattern("\\d{4,}");
    }

    @Test
    @DisplayName("sendTransactionOtp: tiêu đề chứa referenceId của giao dịch, nội dung có OTP và thời hạn")
    void sendTransactionOtp_containsReferenceIdOtpAndTtl() {
        emailService.sendTransactionOtp(TO, OTP, 5, transaction(REFERENCE_ID));

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("Mã xác thực cho giao dịch " + REFERENCE_ID);
        assertThat(message.getText()).contains(OTP).contains("5 phút").contains("Không chia sẻ");
    }

    @Test
    @DisplayName("sendTransactionOtp: hai giao dịch khác nhau cho hai tiêu đề khác nhau (người dùng phân biệt được OTP nào của giao dịch nào)")
    void sendTransactionOtp_subjectDiffersPerTransaction() {
        emailService.sendTransactionOtp(TO, OTP, TTL_MINUTES, transaction("REF-A"));
        emailService.sendTransactionOtp(TO, OTP, TTL_MINUTES, transaction("REF-B"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(SimpleMailMessage::getSubject)
                .containsExactly("Mã xác thực cho giao dịch REF-A", "Mã xác thực cho giao dịch REF-B");
    }

    @Test
    @DisplayName("sendTransactionOtp: transaction = null => ném NullPointerException và KHÔNG gửi mail (AsyncMailRunner sẽ bắt và hoàn lượt)")
    void sendTransactionOtp_nullTransaction_throwsAndSendsNothing() {
        assertThatThrownBy(() -> emailService.sendTransactionOtp(TO, OTP, TTL_MINUTES, null))
                .isInstanceOf(NullPointerException.class);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Lỗi từ JavaMailSender được ném nguyên vẹn ra ngoài (không nuốt lỗi) để AsyncMailRunner xử lý hoàn lượt")
    void mailSenderFailure_propagatesUnchanged() {
        MailSendException failure = new MailSendException("SMTP down");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailService.sendVerificationCode(TO, CODE)).isSameAs(failure);
        assertThatThrownBy(() -> emailService.sendPasswordResetOtp(TO, OTP, TTL_MINUTES)).isSameAs(failure);
        assertThatThrownBy(() -> emailService.sendPasswordChangedNotice(TO)).isSameAs(failure);
        assertThatThrownBy(() -> emailService.sendTryToLoginWarning(TO)).isSameAs(failure);
        assertThatThrownBy(() -> emailService.sendTransactionOtp(TO, OTP, TTL_MINUTES, transaction(REFERENCE_ID)))
                .isSameAs(failure);
    }

    @Test
    @DisplayName("Mỗi lần gọi tạo một SimpleMailMessage mới (không dùng chung object giữa các mail)")
    void eachCall_buildsFreshMessage() {
        emailService.sendVerificationCode("a@gmail.com", "111111");
        emailService.sendVerificationCode("b@gmail.com", "222222");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(captor.capture());
        SimpleMailMessage first = captor.getAllValues().get(0);
        SimpleMailMessage second = captor.getAllValues().get(1);
        assertThat(first).isNotSameAs(second);
        assertThat(first.getTo()).containsExactly("a@gmail.com");
        assertThat(first.getText()).contains("111111").doesNotContain("222222");
        assertThat(second.getTo()).containsExactly("b@gmail.com");
        assertThat(second.getText()).contains("222222").doesNotContain("111111");
    }
}
