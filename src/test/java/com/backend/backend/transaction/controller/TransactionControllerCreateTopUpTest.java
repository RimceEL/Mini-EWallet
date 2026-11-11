package com.backend.backend.transaction.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

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
import com.backend.backend.transaction.dto.TransactionRequest;
import com.backend.backend.wallet.Wallet;
import com.backend.backend.wallet.WalletService;
import com.backend.backend.wallet.customexception.WalletNotFoundException;
import com.backend.backend.transaction.OtpTransactionService;
import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionController;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
public class TransactionControllerCreateTopUpTest {
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

        private static final String URL = "/api/v1/user/transaction/create/top-up";
        private static final String VALID_BODY = "{\"amount\":500000,\"description\":\"Nap tien\"}";

        private ResultActions postTopUp(String json) throws Exception {
                return mockMvc.perform(post(URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andDo(print());
        }

        private Transaction buildSavedTransaction() {
                Transaction saved = new Transaction();
                saved.setReferenceId("ref-1");
                saved.setType(TransactionType.TOP_UP);
                saved.setStatus(TransactionStatus.PENDING);
                saved.setSourceWalletId("wallet-1");
                saved.setDestinationWalletId("wallet-1");
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

        @Test
        void createTopUp_clientSendsExtraFields_areIgnored() throws Exception {
                stubServiceSuccess();
                String body = """
                                {"amount": 1000, "description": "Nap tien",
                                 "type": "TRANSFER", "sourceWalletId": "hacker-wallet",
                                 "destinationWalletId": "hacker-wallet",
                                 "referenceId": "my-ref", "status": "SUCCESS"}
                                """;

                postTopUp(body).andExpect(status().isOk());

                TransactionRequest sent = captureSentRequest();
                assertEquals(TransactionType.TOP_UP, sent.getType());
                assertEquals("wallet-1", sent.getSourceWalletId());
                assertEquals("wallet-1", sent.getDestinationWalletId());
                assertNotEquals("my-ref", sent.getReferenceId());
        }

        @Test
        void createTopUp_userHasNoWallet_returns4xxNotServerError() throws Exception {
                doThrow(new WalletNotFoundException("Ví không tồn tại"))
                                .when(walletService).getWalletDetails("an@gmail.com");

                postTopUp(VALID_BODY).andExpect(status().is4xxClientError());

                verifyNoInteractions(transactionService);
        }

        @Test
        void createTopUp_usesSameReferenceIdToRecordAndLookup() throws Exception {
                stubServiceSuccess();

                postTopUp(VALID_BODY).andExpect(status().isOk());

                TransactionRequest sent = captureSentRequest();
                UUID.fromString(sent.getReferenceId());
                verify(transactionService).findTransactionByRefId(sent.getReferenceId());
        }

        @Test
        void createTopUp_success_returnsTransactionDataInBody() throws Exception {
                stubServiceSuccess();

                postTopUp(VALID_BODY)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.referenceId").value("ref-1"))
                                .andExpect(jsonPath("$.data.type").value("TOP_UP"))
                                .andExpect(jsonPath("$.data.status").value("PENDING"));

        }

        @Test
        void createTopUp_recordsTransactionBeforeLookup() throws Exception {
                stubServiceSuccess();

                postTopUp(VALID_BODY).andExpect(status().isOk());

                InOrder order = inOrder(transactionService);
                order.verify(transactionService).toTransaction(any(TransactionRequest.class));
                order.verify(transactionService).transactionRecord(any(Transaction.class));
                order.verify(transactionService).findTransactionByRefId(anyString());
        }

        @Test
        void createTopUp_malformedJson_returns400AndNoServiceCall() throws Exception {
                postTopUp("{\"amount\":").andExpect(status().isBadRequest());

                verifyNoInteractions(transactionService);
        }

        @Test
        void createTopUp_emptyBody_returns400AndNoServiceCall() throws Exception {
                postTopUp("").andExpect(status().isBadRequest());

                verifyNoInteractions(transactionService);
        }

        @Test
        void createTopUp_amountIsNotANumber_returns400AndNoServiceCall() throws Exception {
                postTopUp("{\"amount\":\"abc\",\"description\":\"x\",\"destinationWalletId\":\"wallet-2\"}")
                                .andExpect(status().isBadRequest());

                verifyNoInteractions(transactionService);
        }

}