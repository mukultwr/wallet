package com.keychain.wallet.repository;

import com.keychain.wallet.model.postgres.WalletBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletBalanceRepository extends JpaRepository<WalletBalance, String> {

    // Acquires a row-level exclusive lock (SELECT ... FOR UPDATE).
    // Used exclusively inside the deduct transaction to prevent concurrent over-spend.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletBalance w WHERE w.walletId = :walletId")
    Optional<WalletBalance> findByWalletIdWithLock(@Param("walletId") String walletId);
}
