package com.backend.backend.Transaction.DTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backend.backend.Transaction.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TopUpResponse {
    private String referenceId;
    private TransactionType type;
    private String sourceWalletId;
    private BigDecimal amount;
    private String description;
    private LocalDateTime createdAt;
}
