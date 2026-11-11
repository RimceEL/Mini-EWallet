package com.backend.backend.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.backend.backend.transaction.Transaction;
import com.backend.backend.transaction.TransactionRepository;
import com.backend.backend.transaction.TransactionService;
import com.backend.backend.transaction.TransactionStatus;
import com.backend.backend.transaction.TransactionType;
import com.backend.backend.transaction.customexception.TransactionError;
import com.backend.backend.transaction.customexception.TransactionNotFound;

@ExtendWith(MockitoExtension.class)
public class TransactionServiceExecuteTest {

    private static final String SOURCE = "wallet-source";
    private static final String DEST = "wallet-dest";
    private static final String REF_ID = "ref-123";
    private static final BigDecimal AMOUNT = new BigDecimal("100000");

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    private static Transaction.TransactionBuilder baseTx() {
        return Transaction.builder()
                .referenceId(REF_ID)
                .status(TransactionStatus.PENDING)
                .amount(AMOUNT)
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

    static Stream<Arguments> allTypes() {
        return Stream.of(
                Arguments.of("TOP_UP", topUp()),
                Arguments.of("WITHDRAW", withdraw()),
                Arguments.of("TRANSFER", transfer()));
    }

    private void stubMoneyOperation(Transaction tx, boolean result) {
        switch (tx.getType()) {
            case TOP_UP -> when(transactionRepository.credit(SOURCE, tx.getAmount())).thenReturn(result);
            case WITHDRAW -> when(transactionRepository.debit(SOURCE, tx.getAmount())).thenReturn(result);
            case TRANSFER -> when(transactionRepository.transfer(SOURCE, DEST, tx.getAmount()))
                    .thenReturn(result);
        }
    }

    @Test
    void execute_whenValidTopUp_shouldMarkSuccessCreditSourceAndDecreasePending() {
        Transaction tx = topUp();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.credit(SOURCE, AMOUNT)).thenReturn(true);
        when(transactionRepository.decreasePending(SOURCE)).thenReturn(true);

        assertThatCode(() -> transactionService.execute(tx)).doesNotThrowAnyException();

        verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        verify(transactionRepository).credit(SOURCE, AMOUNT);
        verify(transactionRepository).decreasePending(SOURCE);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void execute_whenValidWithdraw_shouldMarkSuccessDebitSourceAndDecreasePending() {
        Transaction tx = withdraw();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.debit(SOURCE, AMOUNT)).thenReturn(true);
        when(transactionRepository.decreasePending(SOURCE)).thenReturn(true);

        assertThatCode(() -> transactionService.execute(tx)).doesNotThrowAnyException();

        verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        verify(transactionRepository).debit(SOURCE, AMOUNT);
        verify(transactionRepository).decreasePending(SOURCE);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void execute_whenValidTransfer_shouldTransferFromSourceToDestAndDecreasePendingOfSource() {
        Transaction tx = transfer();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.transfer(SOURCE, DEST, AMOUNT)).thenReturn(true);
        when(transactionRepository.decreasePending(SOURCE)).thenReturn(true);

        assertThatCode(() -> transactionService.execute(tx)).doesNotThrowAnyException();

        InOrder order = inOrder(transactionRepository);
        order.verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        order.verify(transactionRepository).transfer(SOURCE, DEST, AMOUNT);
        order.verify(transactionRepository).decreasePending(SOURCE);
        verifyNoMoreInteractions(transactionRepository);
    }

    @ParameterizedTest(name = "amount = {0}")
    @ValueSource(strings = { "1", "100.00", "10000000000" })
    void execute_whenAmountAtBoundary_shouldPassAmountToRepositoryUnchanged(String amountText) {
        BigDecimal amount = new BigDecimal(amountText);
        Transaction tx = topUp();
        tx.setAmount(amount);
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.credit(SOURCE, amount)).thenReturn(true);
        when(transactionRepository.decreasePending(SOURCE)).thenReturn(true);

        assertThatCode(() -> transactionService.execute(tx)).doesNotThrowAnyException();

        verify(transactionRepository).credit(SOURCE, amount);
    }

    @Test
    void execute_whenDecreasePendingReturnsFalse_shouldStillSucceed() {
        Transaction tx = topUp();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.credit(SOURCE, AMOUNT)).thenReturn(true);
        when(transactionRepository.decreasePending(SOURCE)).thenReturn(false);

        assertThatCode(() -> transactionService.execute(tx)).doesNotThrowAnyException();

        verify(transactionRepository).decreasePending(SOURCE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTypes")
    void execute_whenAlreadySuccess_shouldThrowAndNotMoveMoney(String caseName, Transaction tx) {
        Transaction current = topUp();
        current.setStatus(TransactionStatus.SUCCESS);
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(false);
        when(transactionRepository.findTransactionByRefId(REF_ID)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(TransactionError.class)
                .hasMessage("Giao dịch đã được xử lý trước đó");

        verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        verify(transactionRepository).findTransactionByRefId(REF_ID);
        verifyNoMoreInteractions(transactionRepository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTypes")
    void execute_whenAlreadyFailedOrExpired_shouldThrowExpiredAndNotMoveMoney(String caseName, Transaction tx) {
        Transaction current = topUp();
        current.setStatus(TransactionStatus.FAILED);
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(false);
        when(transactionRepository.findTransactionByRefId(REF_ID)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(TransactionError.class)
                .hasMessage("Giao dịch đã hết hạn, vui lòng tạo giao dịch mới");

        verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        verify(transactionRepository).findTransactionByRefId(REF_ID);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void execute_whenTransactionNotFound_shouldThrowTransactionNotFoundAndNotMoveMoney() {
        Transaction tx = topUp();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(false);
        when(transactionRepository.findTransactionByRefId(REF_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(TransactionNotFound.class)
                .hasMessage("Giao dịch không tồn tại");

        verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        verify(transactionRepository).findTransactionByRefId(REF_ID);
        verifyNoMoreInteractions(transactionRepository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTypes")
    void execute_whenMoneyOperationReturnsFalse_shouldThrowTransactionErrorAndNotDecreasePending(
            String caseName, Transaction tx) {
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        stubMoneyOperation(tx, false);

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(TransactionError.class)
                .hasMessage("Giao dịch thực hiện không thành công");

        verify(transactionRepository, never()).decreasePending(SOURCE);
        verify(transactionRepository, never()).findTransactionByRefId(REF_ID);
    }

    @Test
    void execute_whenUpdateStatusThrowsDataAccessException_shouldPropagateAndNotMoveMoney() {
        Transaction tx = topUp();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(transactionRepository).updateStatus(REF_ID, TransactionStatus.SUCCESS);
        verifyNoMoreInteractions(transactionRepository);
    }

    @Test
    void execute_whenTransferThrowsDeadlock_shouldPropagateAndNotDecreasePending() {
        Transaction tx = transfer();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.transfer(SOURCE, DEST, AMOUNT))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(CannotAcquireLockException.class);

        verify(transactionRepository, never()).decreasePending(SOURCE);
    }

    @Test
    void execute_whenDecreasePendingThrowsDataAccessException_shouldPropagate() {
        Transaction tx = topUp();
        when(transactionRepository.updateStatus(REF_ID, TransactionStatus.SUCCESS)).thenReturn(true);
        when(transactionRepository.credit(SOURCE, AMOUNT)).thenReturn(true);
        when(transactionRepository.decreasePending(SOURCE))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> transactionService.execute(tx))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void execute_shouldBeTransactional() {
        assertThat(TransactionService.class.isAnnotationPresent(Transactional.class)
                || getExecuteMethod().isAnnotationPresent(Transactional.class)).isTrue();
    }

    private java.lang.reflect.Method getExecuteMethod() {
        try {
            return TransactionService.class.getMethod("execute", Transaction.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
