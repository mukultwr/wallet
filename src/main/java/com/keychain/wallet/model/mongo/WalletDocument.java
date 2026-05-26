package com.keychain.wallet.model.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB document for wallet profile (name, email, status). Flexible schema allows adding
 * fields (KYC tier, etc.) without migrations. Balance lives separately in Postgres for ACID guarantees.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "wallets")
public class WalletDocument {

    @Id
    private String id;

    /** Enforces one wallet per customer at the database level. */
    @Indexed(unique = true)
    private String customerId;

    private String name;
    private String email;
    private String phone;

    @Builder.Default
    private WalletStatus status = WalletStatus.ACTIVE;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
