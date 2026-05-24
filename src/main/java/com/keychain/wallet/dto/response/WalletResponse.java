package com.keychain.wallet.dto.response;

import com.keychain.wallet.model.mongo.WalletDocument;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class WalletResponse {

    private String walletId;
    private String customerId;
    private String name;
    private String email;
    private String phone;
    private String status;
    private BigDecimal balanceInr;
    private LocalDateTime createdAt;

    public static WalletResponse from(WalletDocument doc, long balancePaise) {
        return WalletResponse.builder()
                .walletId(doc.getId())
                .customerId(doc.getCustomerId())
                .name(doc.getName())
                .email(doc.getEmail())
                .phone(doc.getPhone())
                .status(doc.getStatus().name())
                .balanceInr(BigDecimal.valueOf(balancePaise, 2))
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
