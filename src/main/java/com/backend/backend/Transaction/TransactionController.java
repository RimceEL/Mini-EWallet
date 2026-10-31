package com.backend.backend.Transaction;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.backend.Auth.Model.User;
import com.backend.backend.DTO.ApiResponse;
import com.backend.backend.Transaction.DTO.SendOtpRequest;
import com.backend.backend.Transaction.DTO.TopUpRequest;
import com.backend.backend.Transaction.DTO.TopUpResponse;
import com.backend.backend.Transaction.DTO.TransactionRequest;
import com.backend.backend.Transaction.DTO.TransactionResponse;
import com.backend.backend.Transaction.DTO.TransferRequest;
import com.backend.backend.Transaction.DTO.VerifyOtpRequest;
import com.backend.backend.Transaction.DTO.VerifyTransactionRequest;
import com.backend.backend.Transaction.DTO.WithdrawRequest;
import com.backend.backend.Wallet.WalletService;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class TransactionController {
    private final TransactionService transactionService;
    private final OtpTransactionService otpTransactionService;
    private final WalletService walletService;

    @PostMapping("/user/transaction/create/top-up")
    public ApiResponse<TransactionResponse> createTopUp(@AuthenticationPrincipal User user,
            @RequestBody TopUpRequest request) {
        Transaction record = createPending(user, TransactionType.TOP_UP, null,
                request.getAmount(), request.getDescription());
        return new ApiResponse<>(0, "Success", TransactionResponse.from(record));
    }

    @PostMapping("/user/transaction/create/withdraw")
    public ApiResponse<TransactionResponse> createWithdraw(@AuthenticationPrincipal User user,
            @RequestBody WithdrawRequest request) {
        Transaction record = createPending(user, TransactionType.WITHDRAW, null,
                request.getAmount(), request.getDescription());
        return new ApiResponse<>(0, "Success", TransactionResponse.from(record));
    }

    @PostMapping("/user/transaction/create/transfer")
    public ApiResponse<TransactionResponse> createTransfer(@AuthenticationPrincipal User user,
            @RequestBody TransferRequest request) {
        Transaction record = createPending(user, TransactionType.TRANSFER, request.getDestinationWalletId(),
                request.getAmount(), request.getDescription());
        return new ApiResponse<>(0, "Success", TransactionResponse.from(record));
    }

    @PostMapping("/user/transaction/verify/send-otp")
    public ApiResponse<String> sendOtpVerify(@AuthenticationPrincipal User user, @RequestBody SendOtpRequest request) {
        Transaction transaction = transactionService.findTransactionByRefId(request.getReferenceId());
        otpTransactionService.transactionRequest(transaction, user.getEmail());
        return new ApiResponse<>(0, "Success", "Đã gửi mã xác thực otp");
    }

    @PostMapping("/user/transaction/verify/verify-otp")
    public ApiResponse<String> verifyOtp(@AuthenticationPrincipal User user, @RequestBody VerifyOtpRequest request) {
        Transaction transaction = transactionService.findTransactionByRefId(request.getReferenceId());
        String token = otpTransactionService.verifyTransactionOtp(transaction, user.getEmail(),
                request.getRawOtp());

        return new ApiResponse<>(0, "Success", token);
    }

    @PostMapping("/user/transaction/execute")
    public ApiResponse<Transaction> excecute(@AuthenticationPrincipal User user,
            @RequestBody VerifyTransactionRequest request) {
        Transaction transaction = transactionService.findTransactionByRefId(request.getReferenceId());
        String rawToken = request.getRawToken();
        otpTransactionService.verifyTransaction(transaction, user.getEmail(), rawToken);
        transaction = transactionService.findTransactionByRefId(transaction.getReferenceId());
        return new ApiResponse<>(0, "Success", transaction);
    }

    private Transaction createPending(User user, TransactionType type, String destinationWalletId,
            BigDecimal amount, String description) {
        String sourceWalletId = walletService.getWalletDetails(user.getEmail()).getId();
        String destWalletId = type == TransactionType.TRANSFER ? destinationWalletId : sourceWalletId;
        String referenceId = UUID.randomUUID().toString();

        transactionService.transactionRecord(transactionService.toTransaction(
                new TransactionRequest(referenceId, type, sourceWalletId, destWalletId, amount, description)));
        return transactionService.findTransactionByRefId(referenceId);
    }

}
