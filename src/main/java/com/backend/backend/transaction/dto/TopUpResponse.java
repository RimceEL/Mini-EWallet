package com.backend.backend.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.backend.backend.transaction.TransactionType;

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
