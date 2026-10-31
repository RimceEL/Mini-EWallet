package com.backend.backend.Transaction;

import java.math.BigDecimal;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.backend.Transaction.CustomException.DuplicateTransactionException;
import com.backend.backend.Transaction.CustomException.InvalidTransactionException;
import com.backend.backend.Transaction.CustomException.NotEnoughAmountException;
import com.backend.backend.Transaction.CustomException.TransactionError;
import com.backend.backend.Transaction.CustomException.TransactionNotFound;
import com.backend.backend.Transaction.CustomException.TransactionRecordError;
import com.backend.backend.Transaction.DTO.TransactionRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000000");
    private final TransactionRepository transactionRepository;

    public Transaction findTransactionByRefId(String referenceId) {
        return transactionRepository.findTransactionByRefId(referenceId)
                .orElseThrow(() -> new TransactionNotFound("Giao dịch không tồn tại"));
    }

    public boolean isUserWallet(String walletId, String email) {
        return transactionRepository.isUserWallet(walletId, email);
    }

    @Transactional
    public void transactionRecord(Transaction transaction) {
        validate(transaction);
        precheck(transaction);

        System.out.println(transaction);

        try {
            transactionRepository.transactionRecord(transaction);
        } catch (DuplicateKeyException e) {
            throw new DuplicateTransactionException(
                    "Giao dịch đã tồn tại: " + transaction.getReferenceId());
        } catch (DataIntegrityViolationException e) {
            throw new InvalidTransactionException(
                    "Dữ liệu giao dịch không hợp lệ hoặc ví không tồn tại");
        } catch (DataAccessException e) {
            e.printStackTrace();
            throw new TransactionRecordError("Không thể ghi giao dịch, vui lòng thử lại sau");
        }
    }

    @Transactional
    public void execute(Transaction transaction) {
        String ref = transaction.getReferenceId();
        if (!transactionRepository.updateStatus(ref, TransactionStatus.SUCCESS)) {
            Transaction current = findTransactionByRefId(ref);
            if (current.getStatus() == TransactionStatus.SUCCESS)
                throw new TransactionError("Giao dịch đã được xử lý trước đó");
            throw new TransactionError("Giao dịch đã hết hạn, vui lòng tạo giao dịch mới");
        }

        boolean result = switch (transaction.getType()) {
            case TOP_UP -> transactionRepository.credit(transaction.getSourceWalletId(), transaction.getAmount());
            case WITHDRAW -> transactionRepository.debit(transaction.getSourceWalletId(), transaction.getAmount());
            case TRANSFER -> transactionRepository.transfer(transaction.getSourceWalletId(),
                    transaction.getDestinationWalletId(), transaction.getAmount());
            default -> false;
        };
        if (!result)
            throw new TransactionError("Giao dịch thực hiện không thành công");
    }

    public Transaction toTransaction(TransactionRequest request) {
        Transaction transaction = new Transaction();
        transaction.setReferenceId(request.getReferenceId());
        transaction.setType(request.getType());
        transaction.setStatus(TransactionStatus.PENDING); // server tự đặt, không lấy từ client
        transaction.setSourceWalletId(request.getSourceWalletId());
        transaction.setDestinationWalletId(request.getDestinationWalletId());
        transaction.setAmount(request.getAmount());
        transaction.setDescription(request.getDescription());
        return transaction;
    }

    private void validate(Transaction t) {
        if (t.getAmount() == null || t.getAmount().signum() <= 0)
            throw new InvalidTransactionException("Số tiền phải lớn hơn 0");
        if (t.getAmount().stripTrailingZeros().scale() > 0) // khớp DECIMAL(19,4)
            throw new InvalidTransactionException("Số tiền không được chứa số thập phân");
        if (t.getAmount().compareTo(MAX_AMOUNT) > 0)
            throw new InvalidTransactionException("Số tiền vượt quá mức cho phép");
        if (t.getType() == null)
            throw new InvalidTransactionException("Giao dịch phải phân biệt loại giao dịch");
        if (t.getSourceWalletId() == null)
            throw new InvalidTransactionException("Giao dịch phải có ví nguồn để xác định người thực hiện");

        boolean hasDest = t.getDestinationWalletId() != null;

        switch (t.getType()) {
            case TOP_UP, WITHDRAW -> {
                if (!t.getSourceWalletId().equals(t.getDestinationWalletId()))
                    throw new InvalidTransactionException("Nạp/rút tiền phải có ví nguồn và ví nhận là cùng một ví");
            }
            case TRANSFER -> {
                if (!hasDest)
                    throw new InvalidTransactionException("Chuyển tiền cần cả hai ví");
                if (t.getSourceWalletId().equals(t.getDestinationWalletId()))
                    throw new InvalidTransactionException("Không thể chuyển cho chính mình");
            }
            default -> throw new InvalidTransactionException("Loại giao dịch này không tồn tại");
        }
    }

    private void precheck(Transaction t) {
        switch (t.getType()) {
            case TOP_UP -> requireActive(t.getSourceWalletId(), "Ví không tồn tại hoặc không hoạt động");
            case WITHDRAW -> requireCanDebit(t);
            case TRANSFER -> {
                requireCanDebit(t);
                requireActive(t.getDestinationWalletId(), "Ví nhận không tồn tại hoặc không hoạt động");
            }
            default -> {
            }
        }
    }

    private void requireActive(String walletId, String msg) {
        if (!transactionRepository.isActiveWallet(walletId))
            throw new InvalidTransactionException(msg);
    }

    private void requireCanDebit(Transaction t) {
        if (!transactionRepository.canDebit(t.getSourceWalletId(), t.getAmount()))
            throw new NotEnoughAmountException("Số dư không đủ hoặc ví không hoạt động");
    }

    @Scheduled(fixedDelay = 60_000)
    public void expirePendingTransactions() {
        transactionRepository.expirePending(30);
    }
}
