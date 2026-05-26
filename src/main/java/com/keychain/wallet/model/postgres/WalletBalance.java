package com.keychain.wallet.model.postgres;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Postgres entity for the authoritative wallet balance, stored in paise (Long) to avoid
 * float precision issues. DB CHECK (balance >= 0) is the last-resort guard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "wallet_balances")
public class WalletBalance {

    @Id
    @Column(name = "wallet_id", length = 36)
    private String walletId;

    /** Balance in paise (₹1 = 100). Never negative — guarded by app logic and DB CHECK. */
    @Column(nullable = false)
    private Long balance;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
