package com.keychain.wallet.model.postgres;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "transactions",
    indexes = @Index(name = "idx_txn_wallet_created", columnList = "wallet_id, created_at DESC")
)
public class Transaction {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "wallet_id", nullable = false, length = 36)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    // Paise
    @Column(nullable = false)
    private Long amount;

    // Snapshot of balance immediately after this transaction committed.
    // Enables full audit reconstruction without summing the ledger.
    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    // UNIQUE constraint in DB; null for topups (not required to be idempotent by spec)
    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "reference_id", length = 255)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
