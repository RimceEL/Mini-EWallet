package com.backend.backend.transaction.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import com.backend.backend.auth.filter.JwtAuthenticationFilter;
import com.backend.backend.auth.model.User;
import com.backend.backend.auth.service.MyUserDetailsService;
import com.backend.backend.transaction.OtpTransactionService;
import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionController;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.backend.backend.transaction.dto.TransactionRequest;
import com.backend.backend.wallet.Wallet;
import com.backend.backend.wallet.WalletService;
import com.backend.backend.wallet.customexception.WalletNotFoundException;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
public class TransactionControllerCreateTransferTest {
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

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn("an@gmail.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        Wallet wallet = mock(Wallet.class);
        when(wallet.getId()).thenReturn("wallet-1");
        when(walletService.getWalletDetails("an@gmail.com")).thenReturn(wallet);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static final String URL = "/api/v1/user/transaction/create/transfer";
    private static final String VALID_BODY = "{\"amount\":500000,\"description\":\"Chuyen tien\",\"destinationWalletId\":\"wallet-2\"}";

    private ResultActions postTransfer(String json) throws Exception {
        return mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andDo(print());
    }

    private Transaction buildSavedTransaction() {
        Transaction saved = new Transaction();
        saved.setReferenceId("ref-1");
        saved.setType(TransactionType.TRANSFER);
        saved.setStatus(TransactionStatus.PENDING);
        saved.setSourceWalletId("wallet-1");
        saved.setDestinationWalletId("wallet-2");
        saved.setAmount(new BigDecimal("500000"));
        return saved;
    }

    private void stubServiceSuccess() {
        Transaction saved = buildSavedTransaction();
        when(transactionService.toTransaction(any(TransactionRequest.class))).thenReturn(saved);
        when(transactionService.findTransactionByRefId(anyString())).thenReturn(saved);
    }

    private TransactionRequest captureSentRequest() {
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).toTransaction(captor.capture());
        return captor.getValue();
    }

    // Với transfer, destinationWalletId là field hợp lệ do client gửi,
    // còn type / sourceWalletId / referenceId / status phải do server quyết định.
    @Test
    void createTransfer_clientSendsExtraFields_areIgnored() throws Exception {
        stubServiceSuccess();
        String body = """
                {"amount": 1000, "description": "Chuyen tien",
                 "destinationWalletId": "wallet-2",
                 "type": "WITHDRAW", "sourceWalletId": "hacker-wallet",
                 "referenceId": "my-ref", "status": "SUCCESS"}
                """;

        postTransfer(body).andExpect(status().isOk());

        TransactionRequest sent = captureSentRequest();
        assertEquals(TransactionType.TRANSFER, sent.getType());
        assertEquals("wallet-1", sent.getSourceWalletId());
        assertEquals("wallet-2", sent.getDestinationWalletId());
        assertNotEquals("my-ref", sent.getReferenceId());
    }

    @Test
    void createTransfer_userHasNoWallet_returns4xxNotServerError() throws Exception {
        doThrow(new WalletNotFoundException("Ví không tồn tại"))
                .when(walletService).getWalletDetails("an@gmail.com");

        postTransfer(VALID_BODY).andExpect(status().is4xxClientError());

        verifyNoInteractions(transactionService);
    }

    @Test
    void createTransfer_usesSameReferenceIdToRecordAndLookup() throws Exception {
        stubServiceSuccess();

        postTransfer(VALID_BODY).andExpect(status().isOk());

        TransactionRequest sent = captureSentRequest();
        UUID.fromString(sent.getReferenceId());
        verify(transactionService).findTransactionByRefId(sent.getReferenceId());
    }

    @Test
    void createTransfer_success_returnsTransactionDataInBody() throws Exception {
        stubServiceSuccess();

        postTransfer(VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceId").value("ref-1"))
                .andExpect(jsonPath("$.data.type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void createTransfer_recordsTransactionBeforeLookup() throws Exception {
        stubServiceSuccess();

        postTransfer(VALID_BODY).andExpect(status().isOk());

        InOrder order = inOrder(transactionService);
        order.verify(transactionService).toTransaction(any(TransactionRequest.class));
        order.verify(transactionService).transactionRecord(any(Transaction.class));
        order.verify(transactionService).findTransactionByRefId(anyString());
    }

    @Test
    void createTransfer_malformedJson_returns400AndNoServiceCall() throws Exception {
        postTransfer("{\"amount\":").andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService);
    }

    @Test
    void createTransfer_emptyBody_returns400AndNoServiceCall() throws Exception {
        postTransfer("").andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService);
    }

    @Test
    void createTransfer_amountIsNotANumber_returns400AndNoServiceCall() throws Exception {
        postTransfer("{\"amount\":\"abc\",\"description\":\"x\",\"destinationWalletId\":\"wallet-2\"}")
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionService);
    }

}