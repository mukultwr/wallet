package com.keychain.wallet.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String walletId, long balancePaise) {
        super(String.format(
            "Insufficient balance in wallet %s. Current: ₹%s, Required: ₹100.00",
            walletId,
            BigDecimal.valueOf(balancePaise, 2)
        ));
    }
}
