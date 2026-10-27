package com.backend.backend.Transaction.DTO;

import java.math.BigDecimal;

import com.backend.backend.Transaction.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {
    private String referenceId;
    private TransactionType type; // TOP_UP, TRANSFER, WITHDRAW
    private String sourceWalletId; // null nếu TOP_UP
    private String destinationWalletId; // null nếu WITHDRAW
    private BigDecimal amount;
    private String description;
}
