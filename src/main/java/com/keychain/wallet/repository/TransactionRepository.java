package com.keychain.wallet.repository;

import com.keychain.wallet.model.postgres.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findByWalletIdOrderByCreatedAtDesc(String walletId);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
