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

    // Stored in paise (Long avoids float precision issues).
    // DB-level CHECK (balance >= 0) is the last line of defence.
    @Column(nullable = false)
    private Long balance;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
