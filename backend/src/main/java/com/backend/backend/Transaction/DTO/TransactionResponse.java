package com.backend.backend.Transaction.DTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backend.backend.Transaction.Transaction;
import com.backend.backend.Transaction.TransactionStatus;
import com.backend.backend.Transaction.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {
    private String referenceId;
    private TransactionType type;
    private TransactionStatus status;
    private String sourceWalletId;
    private String destinationWalletId;
    private BigDecimal amount;
    private String description;
    private LocalDateTime createdAt;

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.getReferenceId(), t.getType(), t.getStatus(),
                t.getSourceWalletId(), t.getDestinationWalletId(), t.getAmount(),
                t.getDescription(), t.getCreatedAt());
    }
}
