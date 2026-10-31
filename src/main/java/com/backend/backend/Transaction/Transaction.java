package com.backend.backend.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {
    private String id; // UUID do DB tự sinh khi insert
    private String referenceId; // chống gửi trùng (idempotency)
    private TransactionType type; // TOP_UP, TRANSFER, WITHDRAW
    private TransactionStatus status; // PENDING, SUCCESS, FAILED
    private String sourceWalletId;
    private String destinationWalletId;
    private BigDecimal amount;
    private String description;
    private LocalDateTime createdAt;

    @Override
    public String toString() {
        return "Transaction [id=" + id + ", referenceId=" + referenceId + ", type=" + type + ", status=" + status
                + ", sourceWalletId=" + sourceWalletId + ", destinationWalletId=" + destinationWalletId + ", amount="
                + amount + ", description=" + description + ", createdAt=" + createdAt + "]";
    }

}
