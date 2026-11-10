package com.backend.backend.transaction.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionRepository;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.backend.backend.transaction.customexception.DuplicateTransactionException;
import com.backend.backend.transaction.customexception.ExceedPendingException;
import com.backend.backend.transaction.customexception.InvalidTransactionException;
import com.backend.backend.transaction.customexception.NotEnoughAmountException;
import com.backend.backend.transaction.customexception.TransactionRecordError;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

        private static final String SOURCE = "wallet-source";
        private static final String DEST = "wallet-dest";
        private static final String REF_ID = "ref-123";

        @Mock
        private TransactionRepository transactionRepository;

        @InjectMocks
        private TransactionService transactionService;

        private static Transaction.TransactionBuilder baseTx() {
                return Transaction.builder()
                                .referenceId(REF_ID)
                                .status(TransactionStatus.PENDING)
                                .amount(new BigDecimal("100000"))
                                .description("test");
        }

        private static Transaction topUp() {
                return baseTx().type(TransactionType.TOP_UP)
                                .sourceWalletId(SOURCE).destinationWalletId(SOURCE).build();
        }

        private static Transaction withdraw() {
                return baseTx().type(TransactionType.WITHDRAW)
                                .sourceWalletId(SOURCE).destinationWalletId(SOURCE).build();
        }

        private static Transaction transfer() {
                return baseTx().type(TransactionType.TRANSFER)
                                .sourceWalletId(SOURCE).destinationWalletId(DEST).build();
        }

        @Test
        void transactionRecord_whenValidTopUp_shouldNotThrowAndRecordTransaction() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx)).thenReturn(true);

                assertThatCode(() -> transactionService.transactionRecord(tx))
                                .doesNotThrowAnyException();

                verify(transactionRepository).isActiveWallet(SOURCE);
                verify(transactionRepository).increasePending(SOURCE);
                verify(transactionRepository).transactionRecord(tx);
                verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        void transactionRecord_whenValidWithdraw_shouldNotThrowAndRecordTransaction() {
                Transaction tx = withdraw();
                when(transactionRepository.canDebit(SOURCE, tx.getAmount())).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx)).thenReturn(true);

                assertThatCode(() -> transactionService.transactionRecord(tx))
                                .doesNotThrowAnyException();

                verify(transactionRepository).canDebit(SOURCE, tx.getAmount());
                verify(transactionRepository).increasePending(SOURCE);
                verify(transactionRepository).transactionRecord(tx);
                verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        void transactionRecord_whenValidTransfer_shouldNotThrowAndRecordTransaction() {
                Transaction tx = transfer();
                when(transactionRepository.canDebit(SOURCE, tx.getAmount())).thenReturn(true);
                when(transactionRepository.isActiveWallet(DEST)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx)).thenReturn(true);

                assertThatCode(() -> transactionService.transactionRecord(tx))
                                .doesNotThrowAnyException();

                verify(transactionRepository).canDebit(SOURCE, tx.getAmount());
                verify(transactionRepository).isActiveWallet(DEST);
                verify(transactionRepository).increasePending(SOURCE);
                verify(transactionRepository).transactionRecord(tx);
                verifyNoMoreInteractions(transactionRepository);
        }

        @Test
        void transactionRecord_whenAmountEqualsMaxAmount_shouldNotThrow() {
                Transaction tx = topUp();
                tx.setAmount(new BigDecimal("10000000000"));
                stubTopUpSuccess(tx);

                assertThatCode(() -> transactionService.transactionRecord(tx))
                                .doesNotThrowAnyException();
        }

        @Test
        void transactionRecord_whenAmountHasOnlyTrailingZeros_shouldNotThrow() {
                Transaction tx = topUp();
                tx.setAmount(new BigDecimal("100.00"));
                stubTopUpSuccess(tx);

                assertThatCode(() -> transactionService.transactionRecord(tx))
                                .doesNotThrowAnyException();
        }

        @Test
        void transactionRecord_whenAmountIsOne_shouldNotThrow() {
                Transaction tx = topUp();
                tx.setAmount(BigDecimal.ONE);
                stubTopUpSuccess(tx);

                assertThatCode(() -> transactionService.transactionRecord(tx))
                                .doesNotThrowAnyException();
        }

        private void stubTopUpSuccess(Transaction tx) {
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx)).thenReturn(true);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidTransactions")
        void transactionRecord_whenParamsInvalid_shouldThrowInvalidTransactionAndNotTouchRepository(
                        String caseName, Transaction tx, String expectedMessage) {

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(InvalidTransactionException.class)
                                .hasMessage(expectedMessage);

                verifyNoInteractions(transactionRepository);
        }

        static Stream<Arguments> invalidTransactions() {
                return Stream.of(
                                Arguments.of("amount = null",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(SOURCE).amount(null).build(),
                                                "Số tiền phải lớn hơn 0"),
                                Arguments.of("amount = 0",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(SOURCE).amount(BigDecimal.ZERO)
                                                                .build(),
                                                "Số tiền phải lớn hơn 0"),
                                Arguments.of("amount âm",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(SOURCE)
                                                                .amount(new BigDecimal("-1")).build(),
                                                "Số tiền phải lớn hơn 0"),
                                Arguments.of("amount có phần thập phân (10.5)",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(SOURCE)
                                                                .amount(new BigDecimal("10.5")).build(),
                                                "Số tiền không được chứa số thập phân"),
                                Arguments.of("amount vượt MAX_AMOUNT 1 đơn vị",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(SOURCE)
                                                                .amount(new BigDecimal("10000000001")).build(),
                                                "Số tiền vượt quá mức cho phép"),

                                Arguments.of("type = null",
                                                baseTx().type(null).sourceWalletId(SOURCE).destinationWalletId(SOURCE)
                                                                .build(),
                                                "Giao dịch phải phân biệt loại giao dịch"),
                                Arguments.of("sourceWalletId = null",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(null)
                                                                .destinationWalletId(null).build(),
                                                "Giao dịch phải có ví nguồn để xác định người thực hiện"),

                                Arguments.of("TOP_UP có ví nhận khác ví nguồn",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(DEST).build(),
                                                "Nạp/rút tiền phải có ví nguồn và ví nhận là cùng một ví"),
                                Arguments.of("TOP_UP không có ví nhận (null)",
                                                baseTx().type(TransactionType.TOP_UP).sourceWalletId(SOURCE)
                                                                .destinationWalletId(null).build(),
                                                "Nạp/rút tiền phải có ví nguồn và ví nhận là cùng một ví"),
                                Arguments.of("WITHDRAW có ví nhận khác ví nguồn",
                                                baseTx().type(TransactionType.WITHDRAW).sourceWalletId(SOURCE)
                                                                .destinationWalletId(DEST).build(),
                                                "Nạp/rút tiền phải có ví nguồn và ví nhận là cùng một ví"),

                                Arguments.of("TRANSFER không có ví nhận (null)",
                                                baseTx().type(TransactionType.TRANSFER).sourceWalletId(SOURCE)
                                                                .destinationWalletId(null).build(),
                                                "Chuyển tiền cần cả hai ví"),
                                Arguments.of("TRANSFER cho chính mình",
                                                baseTx().type(TransactionType.TRANSFER).sourceWalletId(SOURCE)
                                                                .destinationWalletId(SOURCE).build(),
                                                "Không thể chuyển cho chính mình"));
        }

        @Test
        void transactionRecord_whenTopUpWalletNotActive_shouldThrowInvalidTransaction() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(false);

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(InvalidTransactionException.class)
                                .hasMessage("Ví không tồn tại hoặc không hoạt động");

                verify(transactionRepository, never()).increasePending(anyString());
                verify(transactionRepository, never()).transactionRecord(any());
        }

        @Test
        void transactionRecord_whenWithdrawNotEnoughBalance_shouldThrowNotEnoughAmount() {
                Transaction tx = withdraw();
                when(transactionRepository.canDebit(SOURCE, tx.getAmount())).thenReturn(false);

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(NotEnoughAmountException.class)
                                .hasMessage("Số dư không đủ hoặc ví không hoạt động");

                verify(transactionRepository, never()).increasePending(anyString());
                verify(transactionRepository, never()).transactionRecord(any());
        }

        @Test
        void transactionRecord_whenTransferNotEnoughBalance_shouldThrowAndNotCheckDestination() {
                Transaction tx = transfer();
                when(transactionRepository.canDebit(SOURCE, tx.getAmount())).thenReturn(false);

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(NotEnoughAmountException.class);

                verify(transactionRepository, never()).isActiveWallet(anyString());
                verify(transactionRepository, never()).increasePending(anyString());
                verify(transactionRepository, never()).transactionRecord(any());
        }

        @Test
        void transactionRecord_whenTransferDestinationNotActive_shouldThrowInvalidTransaction() {
                Transaction tx = transfer();
                when(transactionRepository.canDebit(SOURCE, tx.getAmount())).thenReturn(true);
                when(transactionRepository.isActiveWallet(DEST)).thenReturn(false);

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(InvalidTransactionException.class)
                                .hasMessage("Ví nhận không tồn tại hoặc không hoạt động");

                verify(transactionRepository, never()).increasePending(anyString());
                verify(transactionRepository, never()).transactionRecord(any());
        }

        @Test
        void transactionRecord_whenIncreasePendingReturnsFalse_shouldThrowExceedPending() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(false);

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(ExceedPendingException.class)
                                .hasMessage("Vượt ngưỡng giao dịch được phép treo cho phép");

                verify(transactionRepository, never()).transactionRecord(any());
        }

        @Test
        void transactionRecord_whenInsertReturnsFalse_shouldThrowTransactionRecordError() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx)).thenReturn(false);

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(TransactionRecordError.class)
                                .hasMessage("Không thể ghi giao dịch, vui lòng thử lại sau");
        }

        @Test
        void transactionRecord_whenDuplicateKey_shouldThrowDuplicateTransaction() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx))
                                .thenThrow(new DuplicateKeyException("duplicate reference_id"));

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(DuplicateTransactionException.class)
                                .hasMessageContaining(REF_ID);
        }

        @Test
        void transactionRecord_whenDataIntegrityViolation_shouldThrowInvalidTransaction() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx))
                                .thenThrow(new DataIntegrityViolationException("FK violation"));

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(InvalidTransactionException.class)
                                .hasMessage("Dữ liệu giao dịch không hợp lệ hoặc ví không tồn tại");
        }

        @Test
        void transactionRecord_whenInsertThrowsGenericDataAccessException_shouldThrowTransactionRecordError() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx))
                                .thenThrow(new DataAccessResourceFailureException("connection lost"));

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(TransactionRecordError.class)
                                .hasMessage("Không thể ghi giao dịch, vui lòng thử lại sau");
        }

        @Test
        void transactionRecord_whenIncreasePendingThrowsDataAccessException_shouldThrowTransactionRecordError() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE))
                                .thenThrow(new DataAccessResourceFailureException("connection lost"));

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(TransactionRecordError.class);

                verify(transactionRepository, never()).transactionRecord(any());
        }

        @Test
        void transactionRecord_whenUnexpectedRuntimeException_shouldPropagateUnchanged() {
                Transaction tx = topUp();
                when(transactionRepository.isActiveWallet(SOURCE)).thenReturn(true);
                when(transactionRepository.increasePending(SOURCE)).thenReturn(true);
                when(transactionRepository.transactionRecord(tx))
                                .thenThrow(new IllegalStateException("boom"));

                assertThatThrownBy(() -> transactionService.transactionRecord(tx))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("boom");
        }
}