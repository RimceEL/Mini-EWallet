package com.backend.backend.transaction.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.backend.backend.auth.customexception.otp.InvalidResetTokenException;
import com.backend.backend.auth.filter.JwtAuthenticationFilter;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.MyUserDetailsService;
import com.backend.backend.transaction.OtpTransactionService;
import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionController;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.backend.backend.transaction.customexception.TransactionNotFound;
import com.backend.backend.wallet.WalletService;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
public class TransactionControllerVerifyFlowTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;

    @MockitoBean
    private OtpTransactionService otpTransactionService;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private MyUserDetailsService myUserDetailsService;

    private static final String EMAIL = "an@gmail.com";
    private static final String HACKER_EMAIL = "hacker@gmail.com";
    private static final String REF = "ref-1";

    private static final String SEND_OTP_URL = "/api/v1/user/transaction/verify/send-otp";
    private static final String VERIFY_OTP_URL = "/api/v1/user/transaction/verify/verify-otp";
    private static final String EXECUTE_URL = "/api/v1/user/transaction/execute";

    private static final String SEND_OTP_BODY = "{\"referenceId\":\"ref-1\"}";
    private static final String VERIFY_OTP_BODY = "{\"referenceId\":\"ref-1\",\"rawOtp\":\"123456\"}";
    private static final String EXECUTE_BODY = "{\"referenceId\":\"ref-1\",\"rawToken\":\"token-1\"}";

    private static final String MALFORMED_BODY = "{\"referenceId\":";

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn(EMAIL);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andDo(print());
    }

    private Transaction buildTransaction(TransactionStatus status) {
        Transaction t = new Transaction();
        t.setReferenceId(REF);
        t.setType(TransactionType.TOP_UP);
        t.setStatus(status);
        t.setSourceWalletId("wallet-1");
        t.setDestinationWalletId("wallet-1");
        t.setAmount(new BigDecimal("500000"));
        return t;
    }

    private Transaction stubTransactionFound() {
        Transaction t = buildTransaction(TransactionStatus.PENDING);
        when(transactionService.findTransactionByRefId(REF)).thenReturn(t);
        return t;
    }

    private void stubTransactionNotFound() {
        doThrow(new TransactionNotFound("Giao dịch không tồn tại"))
                .when(transactionService).findTransactionByRefId(REF);
    }

    // ======================= send-otp =======================

    @Test
    void sendOtp_success_returnsMessageInBody() throws Exception {
        stubTransactionFound();

        postJson(SEND_OTP_URL, SEND_OTP_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("Đã gửi mã xác thực otp"));
    }

    @Test
    void sendOtp_clientSendsExtraEmail_usesPrincipalEmailAndFoundTransaction() throws Exception {
        Transaction found = stubTransactionFound();
        String body = "{\"referenceId\":\"ref-1\",\"email\":\"" + HACKER_EMAIL + "\"}";

        postJson(SEND_OTP_URL, body).andExpect(status().isOk());

        verify(otpTransactionService).transactionRequest(same(found), eq(EMAIL));
        verify(otpTransactionService, never()).transactionRequest(any(Transaction.class), eq(HACKER_EMAIL));
    }

    @Test
    void sendOtp_transactionNotFound_returns4xxAndOtpServiceNotCalled() throws Exception {
        stubTransactionNotFound();

        postJson(SEND_OTP_URL, SEND_OTP_BODY).andExpect(status().is4xxClientError());

        verifyNoInteractions(otpTransactionService);
    }

    @Test
    void sendOtp_malformedJson_returns400AndNoServiceCall() throws Exception {
        postJson(SEND_OTP_URL, MALFORMED_BODY).andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService, otpTransactionService);
    }

    @Test
    void sendOtp_emptyBody_returns400AndNoServiceCall() throws Exception {
        postJson(SEND_OTP_URL, "").andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService, otpTransactionService);
    }

    // ======================= verify-otp =======================

    @Test
    void verifyOtp_success_returnsTokenInBody() throws Exception {
        stubTransactionFound();
        when(otpTransactionService.verifyTransactionOtp(any(Transaction.class), anyString(), anyString()))
                .thenReturn("token-abc");

        postJson(VERIFY_OTP_URL, VERIFY_OTP_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("token-abc"));
    }

    @Test
    void verifyOtp_clientSendsExtraEmail_usesPrincipalEmailAndPassesRawOtp() throws Exception {
        Transaction found = stubTransactionFound();
        String body = "{\"referenceId\":\"ref-1\",\"rawOtp\":\"123456\",\"email\":\"" + HACKER_EMAIL + "\"}";

        postJson(VERIFY_OTP_URL, body).andExpect(status().isOk());

        verify(otpTransactionService).verifyTransactionOtp(same(found), eq(EMAIL), eq("123456"));
        verify(otpTransactionService, never()).verifyTransactionOtp(any(Transaction.class), eq(HACKER_EMAIL),
                anyString());
    }

    @Test
    void verifyOtp_transactionNotFound_returns4xxAndOtpServiceNotCalled() throws Exception {
        stubTransactionNotFound();

        postJson(VERIFY_OTP_URL, VERIFY_OTP_BODY).andExpect(status().is4xxClientError());

        verifyNoInteractions(otpTransactionService);
    }

    @Test
    void verifyOtp_malformedJson_returns400AndNoServiceCall() throws Exception {
        postJson(VERIFY_OTP_URL, MALFORMED_BODY).andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService, otpTransactionService);
    }

    @Test
    void verifyOtp_emptyBody_returns400AndNoServiceCall() throws Exception {
        postJson(VERIFY_OTP_URL, "").andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService, otpTransactionService);
    }

    // ======================= execute =======================

    @Test
    void execute_success_returnsReloadedTransactionInBody() throws Exception {
        Transaction pending = buildTransaction(TransactionStatus.PENDING);
        Transaction executed = buildTransaction(TransactionStatus.SUCCESS);
        // lần 1: trước khi verify (PENDING), lần 2: sau khi execute (SUCCESS)
        when(transactionService.findTransactionByRefId(REF)).thenReturn(pending, executed);

        postJson(EXECUTE_URL, EXECUTE_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceId").value(REF))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        verify(otpTransactionService).verifyTransaction(same(pending), eq(EMAIL), eq("token-1"));
    }

    @Test
    void execute_verificationFails_returns4xxAndDoesNotReloadTransaction() throws Exception {
        stubTransactionFound();
        doThrow(new InvalidResetTokenException("Token đã hết hạn hoặc không hợp lệ"))
                .when(otpTransactionService)
                .verifyTransaction(any(Transaction.class), anyString(), anyString());

        postJson(EXECUTE_URL, EXECUTE_BODY).andExpect(status().is4xxClientError());

        verify(transactionService, times(1)).findTransactionByRefId(REF);
    }

    @Test
    void execute_clientSendsExtraEmail_usesPrincipalEmailAndPassesRawToken() throws Exception {
        Transaction found = stubTransactionFound();
        String body = "{\"referenceId\":\"ref-1\",\"rawToken\":\"token-1\",\"email\":\"" + HACKER_EMAIL + "\"}";

        postJson(EXECUTE_URL, body).andExpect(status().isOk());

        verify(otpTransactionService).verifyTransaction(same(found), eq(EMAIL), eq("token-1"));
        verify(otpTransactionService, never()).verifyTransaction(any(Transaction.class), eq(HACKER_EMAIL),
                anyString());
    }

    @Test
    void execute_transactionNotFound_returns4xxAndOtpServiceNotCalled() throws Exception {
        stubTransactionNotFound();

        postJson(EXECUTE_URL, EXECUTE_BODY).andExpect(status().is4xxClientError());

        verifyNoInteractions(otpTransactionService);
    }

    @Test
    void execute_malformedJson_returns400AndNoServiceCall() throws Exception {
        postJson(EXECUTE_URL, MALFORMED_BODY).andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService, otpTransactionService);
    }

    @Test
    void execute_emptyBody_returns400AndNoServiceCall() throws Exception {
        postJson(EXECUTE_URL, "").andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService, otpTransactionService);
    }

    // ======================= thứ tự gọi =======================

    @Test
    void sendOtp_looksUpTransactionBeforeSendingOtp() throws Exception {
        stubTransactionFound();

        postJson(SEND_OTP_URL, SEND_OTP_BODY).andExpect(status().isOk());

        InOrder order = inOrder(transactionService, otpTransactionService);
        order.verify(transactionService).findTransactionByRefId(REF);
        order.verify(otpTransactionService).transactionRequest(any(Transaction.class), anyString());
    }

    @Test
    void verifyOtp_looksUpTransactionBeforeVerifyingOtp() throws Exception {
        stubTransactionFound();

        postJson(VERIFY_OTP_URL, VERIFY_OTP_BODY).andExpect(status().isOk());

        InOrder order = inOrder(transactionService, otpTransactionService);
        order.verify(transactionService).findTransactionByRefId(REF);
        order.verify(otpTransactionService).verifyTransactionOtp(any(Transaction.class), anyString(), anyString());
    }

    @Test
    void execute_verifiesBetweenTwoLookups() throws Exception {
        Transaction pending = buildTransaction(TransactionStatus.PENDING);
        Transaction executed = buildTransaction(TransactionStatus.SUCCESS);
        when(transactionService.findTransactionByRefId(REF)).thenReturn(pending, executed);

        postJson(EXECUTE_URL, EXECUTE_BODY).andExpect(status().isOk());

        InOrder order = inOrder(transactionService, otpTransactionService);
        order.verify(transactionService).findTransactionByRefId(REF);
        order.verify(otpTransactionService).verifyTransaction(any(Transaction.class), anyString(), anyString());
        order.verify(transactionService).findTransactionByRefId(REF);
    }

}