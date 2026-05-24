package com.keychain.wallet.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {

    private String walletId;
    private BigDecimal balanceInr;
    private Long balancePaise;

    public static BalanceResponse of(String walletId, long paise) {
        return BalanceResponse.builder()
                .walletId(walletId)
                .balancePaise(paise)
                .balanceInr(BigDecimal.valueOf(paise, 2))
                .build();
    }
}
