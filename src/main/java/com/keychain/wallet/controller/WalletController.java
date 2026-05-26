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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for wallet operations. Role rules live in SecurityConfig;
 * CUSTOMER ownership (own wallet only) is enforced via checkOwnership().
 */
@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** Creates a new wallet. ADMIN only — customers cannot self-provision. */
    @PostMapping
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            @Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = walletService.createWallet(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** Credits a wallet. CUSTOMER can only top up their own wallet (ownership-checked). */
    @PostMapping("/{id}/topup")
    public ResponseEntity<ApiResponse<TransactionResponse>> topUp(
            @PathVariable String id,
            @Valid @RequestBody TopUpRequest request,
            Authentication auth) {
        checkOwnership(id, auth);
        TransactionResponse response = walletService.topUp(id, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** Deducts ₹100. Idempotency-Key header is required; use the order UUID as the key. */
    @PostMapping("/{id}/deduct")
    public ResponseEntity<ApiResponse<TransactionResponse>> deduct(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) DeductRequest request) {
        String referenceId = (request != null) ? request.getReferenceId() : null;
        TransactionResponse response = walletService.deduct(id, idempotencyKey, referenceId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** Returns the current balance. CUSTOMER can only read their own wallet. */
    @GetMapping("/{id}/balance")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
            @PathVariable String id,
            Authentication auth) {
        checkOwnership(id, auth);
        BalanceResponse response = walletService.getBalance(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** Returns transaction history newest-first. CUSTOMER can only read their own wallet. */
    @GetMapping("/{id}/transactions")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getTransactions(
            @PathVariable String id,
            Authentication auth) {
        checkOwnership(id, auth);
        List<TransactionResponse> response = walletService.getTransactions(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /** CUSTOMER tokens may only access their own wallet; SERVICE and ADMIN bypass this check. */
    private void checkOwnership(String walletId, Authentication auth) {
        if (auth == null) return;
        boolean isCustomer = auth.getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        if (!isCustomer) return;

        String customerId = (String) auth.getPrincipal();
        WalletResponse wallet = walletService.getWalletById(walletId);
        if (!customerId.equals(wallet.getCustomerId())) {
            throw new AccessDeniedException("You can only access your own wallet");
        }
    }
}
