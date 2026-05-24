package com.keychain.wallet.dto.request;

import lombok.Data;

@Data
public class DeductRequest {

    // Optional: the Order Service may pass the order ID here for traceability.
    // The primary deduplication key is the Idempotency-Key header.
    private String referenceId;
}
