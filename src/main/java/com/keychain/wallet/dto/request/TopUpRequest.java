package com.keychain.wallet.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
public class TopUpRequest {

    @NotNull
    @DecimalMin(value = "1.00", message = "Minimum top-up amount is ₹1")
    @DecimalMax(value = "100000.00", message = "Maximum top-up amount is ₹1,00,000")
    private BigDecimal amount; // in INR

    public long toAmountPaise() {
        return amount.multiply(BigDecimal.valueOf(100))
                     .setScale(0, RoundingMode.UNNECESSARY)
                     .longValueExact();
    }
}
