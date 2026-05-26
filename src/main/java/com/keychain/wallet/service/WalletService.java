package com.keychain.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keychain.wallet.dto.request.CreateWalletRequest;
import com.keychain.wallet.dto.request.TopUpRequest;
import com.keychain.wallet.dto.response.BalanceResponse;
import com.keychain.wallet.dto.response.TransactionResponse;
import com.keychain.wallet.dto.response.WalletResponse;
import com.keychain.wallet.exception.InsufficientBalanceException;
import com.keychain.wallet.exception.WalletNotFoundException;
import com.keychain.wallet.model.mongo.WalletDocument;
import com.keychain.wallet.model.mongo.WalletStatus;
import com.keychain.wallet.model.postgres.OutboxEvent;
import com.keychain.wallet.model.postgres.Transaction;
import com.keychain.wallet.model.postgres.TransactionStatus;
import com.keychain.wallet.model.postgres.TransactionType;
import com.keychain.wallet.model.postgres.WalletBalance;
import com.keychain.wallet.repository.OutboxEventRepository;
import com.keychain.wallet.repository.TransactionRepository;
import com.keychain.wallet.repository.WalletBalanceRepository;
import com.keychain.wallet.repository.WalletDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Core business logic for the wallet: create, topup, deduct, balance, and transaction history.
 * Deduct uses a Redisson distributed lock + Postgres SELECT FOR UPDATE to prevent concurrent over-spend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    /** Fixed deduction per order: ₹100 stored as paise to avoid float precision issues. */
    static final long DEDUCTION_AMOUNT_PAISE = 10_000L;

    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final String BALANCE_PREFIX     = "balance:";
    private static final String LOCK_PREFIX        = "lock:wallet:";

    private final WalletDocumentRepository walletDocumentRepository;
    private final WalletBalanceRepository  walletBalanceRepository;
    private final TransactionRepository    transactionRepository;
    private final OutboxEventRepository    outboxEventRepository;
    private final RedissonClient           redissonClient;
    private final StringRedisTemplate      redisTemplate;
    private final ObjectMapper             objectMapper;
    private final TransactionTemplate      transactionTemplate;

    // -------------------------------------------------------------------------
    // Create wallet
    // -------------------------------------------------------------------------

    /** Fetches wallet profile (MongoDB) and current balance (PostgreSQL) by wallet ID. */
    public WalletResponse getWalletById(String walletId) {
        WalletDocument doc = walletDocumentRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        WalletBalance balance = walletBalanceRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        return WalletResponse.from(doc, balance.getBalance());
    }

    /**
     * Creates the wallet profile in MongoDB then the balance row in Postgres.
     * If the Postgres write fails, the MongoDB document is deleted (compensating transaction).
     */
    public WalletResponse createWallet(CreateWalletRequest request) {
        String walletId = UUID.randomUUID().toString();

        WalletDocument doc = WalletDocument.builder()
                .id(walletId)
                .customerId(request.getCustomerId())
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .status(WalletStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        walletDocumentRepository.save(doc);

        try {
            WalletBalance balance = WalletBalance.builder()
                    .walletId(walletId)
                    .balance(0L)
                    .updatedAt(LocalDateTime.now())
                    .build();
            walletBalanceRepository.save(balance);
        } catch (Exception e) {
            // Compensate: remove the Mongo document so we don't have an orphaned profile
            walletDocumentRepository.deleteById(walletId);
            throw new RuntimeException("Wallet creation failed", e);
        }

        return WalletResponse.from(doc, 0L);
    }

    // -------------------------------------------------------------------------
    // Top up
    // -------------------------------------------------------------------------

    /** Credits the wallet; balance update, transaction record, and outbox event commit atomically. */
    public TransactionResponse topUp(String walletId, TopUpRequest request) {
        WalletDocument wallet = walletDocumentRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet " + walletId + " is not active");
        }

        long amountPaise = request.toAmountPaise();

        TransactionResponse response = transactionTemplate.execute(status -> {
            WalletBalance balance = walletBalanceRepository.findByWalletIdWithLock(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));

            long newBalance = balance.getBalance() + amountPaise;
            balance.setBalance(newBalance);
            balance.setUpdatedAt(LocalDateTime.now());
            walletBalanceRepository.save(balance);

            Transaction txn = Transaction.builder()
                    .id(UUID.randomUUID().toString())
                    .walletId(walletId)
                    .type(TransactionType.TOPUP)
                    .amount(amountPaise)
                    .balanceAfter(newBalance)
                    .status(TransactionStatus.SUCCESS)
                    .createdAt(LocalDateTime.now())
                    .build();
            transactionRepository.save(txn);

            appendOutboxEvent(walletId, "WALLET_TOPPED_UP",
                    "{\"walletId\":\"" + walletId + "\",\"amount\":" + amountPaise + "}");

            return TransactionResponse.from(txn);
        });

        redisTemplate.delete(BALANCE_PREFIX + walletId);
        return response;
    }

    // -------------------------------------------------------------------------
    // Deduct — the critical path
    // -------------------------------------------------------------------------

    /**
     * Deducts ₹100 from the wallet. Idempotent via Redis cache (24h) + DB UNIQUE constraint.
     * Concurrency-safe via Redisson distributed lock + Postgres SELECT FOR UPDATE.
     */
    public TransactionResponse deduct(String walletId, String idempotencyKey, String referenceId) {
        // Fast path: idempotency cache hit
        TransactionResponse cached = getCachedIdempotencyResponse(idempotencyKey);
        if (cached != null) {
            log.info("Idempotency cache hit for key={}", idempotencyKey);
            return cached;
        }

        RLock lock = redissonClient.getLock(LOCK_PREFIX + walletId);
        try {
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) {
                throw new RuntimeException("Could not acquire wallet lock — please retry");
            }

            // Double-check after acquiring the lock: another thread may have just committed
            cached = getCachedIdempotencyResponse(idempotencyKey);
            if (cached != null) {
                return cached;
            }

            TransactionResponse response = transactionTemplate.execute(txStatus -> {
                WalletDocument wallet = walletDocumentRepository.findById(walletId)
                        .orElseThrow(() -> new WalletNotFoundException(walletId));

                if (wallet.getStatus() != WalletStatus.ACTIVE) {
                    throw new IllegalStateException("Wallet " + walletId + " is not active");
                }

                // Row-level exclusive lock — blocks any concurrent deduct on this wallet
                WalletBalance balance = walletBalanceRepository.findByWalletIdWithLock(walletId)
                        .orElseThrow(() -> new WalletNotFoundException(walletId));

                if (balance.getBalance() < DEDUCTION_AMOUNT_PAISE) {
                    throw new InsufficientBalanceException(walletId, balance.getBalance());
                }

                long newBalance = balance.getBalance() - DEDUCTION_AMOUNT_PAISE;
                balance.setBalance(newBalance);
                balance.setUpdatedAt(LocalDateTime.now());
                walletBalanceRepository.save(balance);

                Transaction txn = Transaction.builder()
                        .id(UUID.randomUUID().toString())
                        .walletId(walletId)
                        .type(TransactionType.DEDUCT)
                        .amount(DEDUCTION_AMOUNT_PAISE)
                        .balanceAfter(newBalance)
                        .idempotencyKey(idempotencyKey)
                        .referenceId(referenceId)
                        .status(TransactionStatus.SUCCESS)
                        .createdAt(LocalDateTime.now())
                        .build();
                transactionRepository.save(txn);

                appendOutboxEvent(walletId, "WALLET_DEDUCTED",
                        "{\"walletId\":\"" + walletId + "\",\"referenceId\":\"" + referenceId + "\"}");

                return TransactionResponse.from(txn);
            });

            cacheIdempotencyResponse(idempotencyKey, response);
            redisTemplate.delete(BALANCE_PREFIX + walletId);
            return response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock acquisition interrupted", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Balance
    // -------------------------------------------------------------------------

    /** Returns the balance, served from a 1-second Redis read cache to absorb burst reads. */
    public BalanceResponse getBalance(String walletId) {
        walletDocumentRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        String cached = redisTemplate.opsForValue().get(BALANCE_PREFIX + walletId);
        if (cached != null) {
            return BalanceResponse.of(walletId, Long.parseLong(cached));
        }

        WalletBalance balance = walletBalanceRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        redisTemplate.opsForValue().set(BALANCE_PREFIX + walletId,
                balance.getBalance().toString(), 1, TimeUnit.SECONDS);

        return BalanceResponse.of(walletId, balance.getBalance());
    }

    // -------------------------------------------------------------------------
    // Transactions
    // -------------------------------------------------------------------------

    /** Returns all transactions for the wallet ordered newest-first. */
    public List<TransactionResponse> getTransactions(String walletId) {
        walletDocumentRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(walletId)
                .stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the cached deduct response for the given idempotency key, or null on miss. */
    private TransactionResponse getCachedIdempotencyResponse(String key) {
        try {
            String json = redisTemplate.opsForValue().get(IDEMPOTENCY_PREFIX + key);
            if (json != null) {
                return objectMapper.readValue(json, TransactionResponse.class);
            }
        } catch (Exception e) {
            log.warn("Failed to read idempotency cache for key={}", key, e);
        }
        return null;
    }

    /** Caches the deduct response for 24h. Failures are swallowed — cache miss is recoverable. */
    private void cacheIdempotencyResponse(String key, TransactionResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    IDEMPOTENCY_PREFIX + key,
                    objectMapper.writeValueAsString(response),
                    24, TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.warn("Failed to write idempotency cache for key={}", key, e);
        }
    }

    /** Writes an outbox event atomically with the balance update for downstream Kafka publishing. */
    private void appendOutboxEvent(String walletId, String eventType, String payload) {
        outboxEventRepository.save(OutboxEvent.builder()
                .id(UUID.randomUUID().toString())
                .walletId(walletId)
                .eventType(eventType)
                .payload(payload)
                .published(false)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
