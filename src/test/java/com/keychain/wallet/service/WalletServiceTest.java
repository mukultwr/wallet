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
import com.keychain.wallet.model.postgres.Transaction;
import com.keychain.wallet.model.postgres.TransactionStatus;
import com.keychain.wallet.model.postgres.TransactionType;
import com.keychain.wallet.model.postgres.WalletBalance;
import com.keychain.wallet.repository.OutboxEventRepository;
import com.keychain.wallet.repository.TransactionRepository;
import com.keychain.wallet.repository.WalletBalanceRepository;
import com.keychain.wallet.repository.WalletDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock WalletDocumentRepository walletDocumentRepository;
    @Mock WalletBalanceRepository  walletBalanceRepository;
    @Mock TransactionRepository    transactionRepository;
    @Mock OutboxEventRepository    outboxEventRepository;
    @Mock RedissonClient           redissonClient;
    @Mock StringRedisTemplate      redisTemplate;
    @Mock ObjectMapper             objectMapper;
    @Mock TransactionTemplate      transactionTemplate;
    @Mock RLock                    rLock;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks
    WalletService walletService;

    private static final String WALLET_ID = "wallet-001";
    private static final String CUSTOMER_ID = "cust-001";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // -------------------------------------------------------------------------
    // createWallet
    // -------------------------------------------------------------------------

    @Test
    void createWallet_persistsBothMongoAndPostgres() {
        CreateWalletRequest req = new CreateWalletRequest();
        req.setCustomerId(CUSTOMER_ID);
        req.setName("Rahul Sharma");
        req.setEmail("rahul@example.com");
        req.setPhone("+919999999999");

        when(walletDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletBalanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.createWallet(req);

        assertThat(response.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.getBalanceInr()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(walletDocumentRepository).save(any(WalletDocument.class));
        verify(walletBalanceRepository).save(any(WalletBalance.class));
    }

    @Test
    void createWallet_compensatesMongoWhenPostgresFails() {
        CreateWalletRequest req = new CreateWalletRequest();
        req.setCustomerId(CUSTOMER_ID);
        req.setName("Test");
        req.setEmail("t@t.com");
        req.setPhone("+91123");

        when(walletDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletBalanceRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> walletService.createWallet(req))
                .isInstanceOf(RuntimeException.class);

        verify(walletDocumentRepository).deleteById(anyString());
    }

    // -------------------------------------------------------------------------
    // topUp
    // -------------------------------------------------------------------------

    @Test
    void topUp_increasesBalance() {
        TopUpRequest req = new TopUpRequest();
        req.setAmount(new BigDecimal("500.00"));

        WalletDocument wallet = activeWallet();
        WalletBalance balance = walletBalance(0L);
        Transaction txn = savedTransaction(TransactionType.TOPUP, 50_000L, 50_000L, null);

        when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet));
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            when(walletBalanceRepository.findByWalletIdWithLock(WALLET_ID)).thenReturn(Optional.of(balance));
            when(walletBalanceRepository.save(any())).thenReturn(balance);
            when(transactionRepository.save(any())).thenReturn(txn);
            when(outboxEventRepository.save(any())).thenReturn(null);
            return cb.doInTransaction(null);
        });

        TransactionResponse response = walletService.topUp(WALLET_ID, req);

        assertThat(response.getAmountInr()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(response.getBalanceAfterInr()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(balance.getBalance()).isEqualTo(50_000L);
    }

    @Test
    void topUp_throwsWhenWalletNotFound() {
        when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.empty());
        TopUpRequest req = new TopUpRequest();
        req.setAmount(BigDecimal.TEN);

        assertThatThrownBy(() -> walletService.topUp(WALLET_ID, req))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void topUp_throwsWhenWalletSuspended() {
        WalletDocument wallet = activeWallet();
        wallet.setStatus(WalletStatus.SUSPENDED);
        when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet));

        TopUpRequest req = new TopUpRequest();
        req.setAmount(BigDecimal.TEN);

        assertThatThrownBy(() -> walletService.topUp(WALLET_ID, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    // -------------------------------------------------------------------------
    // deduct
    // -------------------------------------------------------------------------

    @Test
    void deduct_succeeds_whenBalanceSufficient() throws Exception {
        WalletDocument wallet = activeWallet();
        WalletBalance balance = walletBalance(10_000L); // exactly ₹100
        Transaction txn = savedTransaction(TransactionType.DEDUCT, 10_000L, 0L, "key-1");

        when(valueOps.get(startsWith("idempotency:"))).thenReturn(null);
        doReturn(rLock).when(redissonClient).getLock(anyString());
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet));
            when(walletBalanceRepository.findByWalletIdWithLock(WALLET_ID)).thenReturn(Optional.of(balance));
            when(walletBalanceRepository.save(any())).thenReturn(balance);
            when(transactionRepository.save(any())).thenReturn(txn);
            when(outboxEventRepository.save(any())).thenReturn(null);
            return cb.doInTransaction(null);
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        TransactionResponse response = walletService.deduct(WALLET_ID, "key-1", "ORDER-1");

        assertThat(response.getType()).isEqualTo("DEDUCT");
        assertThat(balance.getBalance()).isEqualTo(0L);
        verify(rLock).unlock();
    }

    @Test
    void deduct_fails_whenBalanceInsufficient() throws Exception {
        WalletDocument wallet = activeWallet();
        WalletBalance balance = walletBalance(9_999L); // one paise short

        when(valueOps.get(anyString())).thenReturn(null);
        doReturn(rLock).when(redissonClient).getLock(anyString());
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet));
            when(walletBalanceRepository.findByWalletIdWithLock(WALLET_ID)).thenReturn(Optional.of(balance));
            return cb.doInTransaction(null);
        });

        assertThatThrownBy(() -> walletService.deduct(WALLET_ID, "key-2", null))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("₹99.99");

        verify(rLock).unlock();
    }

    @Test
    void deduct_isIdempotent_returnsCachedResponseOnRetry() throws Exception {
        String cachedJson = "{\"transactionId\":\"txn-1\",\"walletId\":\"wallet-001\","
                + "\"type\":\"DEDUCT\",\"status\":\"SUCCESS\"}";
        TransactionResponse expected = TransactionResponse.builder()
                .transactionId("txn-1").walletId(WALLET_ID).type("DEDUCT").status("SUCCESS").build();

        when(valueOps.get("idempotency:key-dup")).thenReturn(cachedJson);
        when(objectMapper.readValue(cachedJson, TransactionResponse.class)).thenReturn(expected);

        TransactionResponse response = walletService.deduct(WALLET_ID, "key-dup", null);

        assertThat(response.getTransactionId()).isEqualTo("txn-1");
        // Lock was never acquired — short-circuited at the cache layer
        verify(redissonClient, never()).getLock(any());
        verify(transactionTemplate, never()).execute(any());
    }

    // -------------------------------------------------------------------------
    // getBalance
    // -------------------------------------------------------------------------

    @Test
    void getBalance_returnsCachedValue_whenPresent() {
        when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(activeWallet()));
        when(valueOps.get(WALLET_ID.transform(id -> "balance:" + id))).thenReturn(null);
        when(valueOps.get("balance:" + WALLET_ID)).thenReturn("25000");

        BalanceResponse response = walletService.getBalance(WALLET_ID);

        assertThat(response.getBalancePaise()).isEqualTo(25_000L);
        assertThat(response.getBalanceInr()).isEqualByComparingTo(new BigDecimal("250.00"));
        verify(walletBalanceRepository, never()).findById(any());
    }

    @Test
    void getBalance_hitsDbAndCaches_onCacheMiss() {
        when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(activeWallet()));
        when(valueOps.get("balance:" + WALLET_ID)).thenReturn(null);
        when(walletBalanceRepository.findById(WALLET_ID))
                .thenReturn(Optional.of(walletBalance(50_000L)));

        BalanceResponse response = walletService.getBalance(WALLET_ID);

        assertThat(response.getBalancePaise()).isEqualTo(50_000L);
        verify(valueOps).set(eq("balance:" + WALLET_ID), eq("50000"), eq(1L), eq(TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // getTransactions
    // -------------------------------------------------------------------------

    @Test
    void getTransactions_returnsLedgerInDescendingOrder() {
        when(walletDocumentRepository.findById(WALLET_ID)).thenReturn(Optional.of(activeWallet()));
        Transaction t1 = savedTransaction(TransactionType.TOPUP, 50_000L, 50_000L, null);
        Transaction t2 = savedTransaction(TransactionType.DEDUCT, 10_000L, 40_000L, "k1");
        when(transactionRepository.findByWalletIdOrderByCreatedAtDesc(WALLET_ID))
                .thenReturn(List.of(t2, t1));

        List<TransactionResponse> txns = walletService.getTransactions(WALLET_ID);

        assertThat(txns).hasSize(2);
        assertThat(txns.get(0).getType()).isEqualTo("DEDUCT");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WalletDocument activeWallet() {
        return WalletDocument.builder()
                .id(WALLET_ID)
                .customerId(CUSTOMER_ID)
                .name("Test User")
                .status(WalletStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private WalletBalance walletBalance(long paise) {
        return WalletBalance.builder()
                .walletId(WALLET_ID)
                .balance(paise)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Transaction savedTransaction(TransactionType type, long amount, long balanceAfter, String key) {
        return Transaction.builder()
                .id(UUID.randomUUID().toString())
                .walletId(WALLET_ID)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .idempotencyKey(key)
                .status(TransactionStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
