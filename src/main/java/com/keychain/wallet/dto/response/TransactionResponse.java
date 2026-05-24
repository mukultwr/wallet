package com.keychain.wallet.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keychain.wallet.model.postgres.Transaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResponse {

    private String transactionId;
    private String walletId;
    private String type;
    private BigDecimal amountInr;
    private BigDecimal balanceAfterInr;
    private String idempotencyKey;
    private String referenceId;
    private String status;
    private LocalDateTime createdAt;

    public static TransactionResponse from(Transaction txn) {
        return TransactionResponse.builder()
                .transactionId(txn.getId())
                .walletId(txn.getWalletId())
                .type(txn.getType().name())
                .amountInr(BigDecimal.valueOf(txn.getAmount(), 2))
                .balanceAfterInr(BigDecimal.valueOf(txn.getBalanceAfter(), 2))
                .idempotencyKey(txn.getIdempotencyKey())
                .referenceId(txn.getReferenceId())
                .status(txn.getStatus().name())
                .createdAt(txn.getCreatedAt())
                .build();
    }
}
