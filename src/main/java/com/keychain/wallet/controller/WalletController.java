package com.keychain.wallet.controller;

import com.keychain.wallet.dto.request.CreateWalletRequest;
import com.keychain.wallet.dto.request.DeductRequest;
import com.keychain.wallet.dto.request.TopUpRequest;
import com.keychain.wallet.dto.response.ApiResponse;
import com.keychain.wallet.dto.response.BalanceResponse;
import com.keychain.wallet.dto.response.TransactionResponse;
import com.keychain.wallet.dto.response.WalletResponse;
import com.keychain.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            @Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = walletService.createWallet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/{id}/topup")
    public ResponseEntity<ApiResponse<TransactionResponse>> topUp(
            @PathVariable String id,
            @Valid @RequestBody TopUpRequest request) {
        TransactionResponse response = walletService.topUp(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // Idempotency-Key header is REQUIRED — the Order Service must supply a unique key
    // (e.g. order_id) so that network retries never double-charge a wallet.
    @PostMapping("/{id}/deduct")
    public ResponseEntity<ApiResponse<TransactionResponse>> deduct(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) DeductRequest request) {
        String referenceId = (request != null) ? request.getReferenceId() : null;
        TransactionResponse response = walletService.deduct(id, idempotencyKey, referenceId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(@PathVariable String id) {
        BalanceResponse response = walletService.getBalance(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getTransactions(
            @PathVariable String id) {
        List<TransactionResponse> response = walletService.getTransactions(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
