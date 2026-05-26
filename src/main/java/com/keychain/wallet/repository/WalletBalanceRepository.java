package com.keychain.wallet.repository;

import com.keychain.wallet.model.postgres.WalletBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** JPA repository for wallet_balances. The locking variant is the second concurrency guard after Redisson. */
public interface WalletBalanceRepository extends JpaRepository<WalletBalance, String> {

    /** Issues SELECT FOR UPDATE — must be called inside an active transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletBalance w WHERE w.walletId = :walletId")
    Optional<WalletBalance> findByWalletIdWithLock(@Param("walletId") String walletId);
}
