package com.keychain.wallet.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keychain.wallet.dto.request.CreateWalletRequest;
import com.keychain.wallet.dto.request.TopUpRequest;
import com.keychain.wallet.dto.response.ApiResponse;
import com.keychain.wallet.dto.response.BalanceResponse;
import com.keychain.wallet.dto.response.WalletResponse;
import com.keychain.wallet.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class WalletControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("wallet_db")
            .withUsername("wallet_user")
            .withPassword("wallet_pass");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:6");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtUtil jwtUtil;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtil.generate("ADMIN-TEST", "ADMIN");
    }

    // -------------------------------------------------------------------------
    // Full happy path
    // -------------------------------------------------------------------------

    @Test
    void fullFlow_createTopUpDeductBalance() throws Exception {
        String walletId = createWallet();

        // Top up ₹200
        topUp(walletId, "200.00");

        // Deduct ₹100 (order 1)
        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                       .header("Authorization", "Bearer " + adminToken)
                       .header("Idempotency-Key", "ORDER-001")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{\"referenceId\":\"ORDER-001\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value(true))
               .andExpect(jsonPath("$.data.type").value("DEDUCT"))
               .andExpect(jsonPath("$.data.balanceAfterInr").value(100.00));

        // Balance should be ₹100
        String balanceJson = mockMvc.perform(get("/wallets/{id}/balance", walletId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ApiResponse<BalanceResponse> balanceResp = objectMapper.readValue(
                balanceJson,
                objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, BalanceResponse.class));
        assertThat(balanceResp.getData().getBalancePaise()).isEqualTo(10_000L);
    }

    // -------------------------------------------------------------------------
    // Hard case 1: Exact balance boundary — succeeds at ₹100, fails at ₹99.99
    // -------------------------------------------------------------------------

    @Test
    void deduct_succeedsAtExactlyOneHundredRupees() throws Exception {
        String walletId = createWallet();
        topUp(walletId, "100.00"); // exactly ₹100

        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                       .header("Authorization", "Bearer " + adminToken)
                       .header("Idempotency-Key", UUID.randomUUID().toString())
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data.balanceAfterInr").value(0.00));
    }

    @Test
    void deduct_failsOneRupeeBelowThreshold() throws Exception {
        String walletId = createWallet();
        topUp(walletId, "99.00"); // ₹1 short

        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                       .header("Authorization", "Bearer " + adminToken)
                       .header("Idempotency-Key", UUID.randomUUID().toString())
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{}"))
               .andExpect(status().isUnprocessableEntity())
               .andExpect(jsonPath("$.success").value(false))
               .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Insufficient")));
    }

    // -------------------------------------------------------------------------
    // Hard case 2: Idempotency — same key returns same response, no double deduct
    // -------------------------------------------------------------------------

    @Test
    void deduct_isIdempotent_sameKeyTwice() throws Exception {
        String walletId = createWallet();
        topUp(walletId, "200.00");

        String idempotencyKey = "ORDER-IDEM-" + UUID.randomUUID();
        String body = "{\"referenceId\":\"" + idempotencyKey + "\"}";

        // First call
        MvcResult first = mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // Second call with SAME key — must return identical transactionId
        MvcResult second = mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                .header("Authorization", "Bearer " + adminToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String txn1 = extractTransactionId(first.getResponse().getContentAsString());
        String txn2 = extractTransactionId(second.getResponse().getContentAsString());
        assertThat(txn1).isEqualTo(txn2);

        // Balance must reflect only ONE deduction
        String balanceJson = mockMvc.perform(get("/wallets/{id}/balance", walletId)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        ApiResponse<BalanceResponse> br = objectMapper.readValue(balanceJson,
                objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, BalanceResponse.class));
        assertThat(br.getData().getBalancePaise()).isEqualTo(10_000L); // ₹200 - ₹100 = ₹100
    }

    // -------------------------------------------------------------------------
    // Hard case 3: Concurrent deductions — wallet must never go negative
    // -------------------------------------------------------------------------

    @Test
    void deduct_concurrently_walletNeverGoesNegative() throws Exception {
        String walletId = createWallet();
        topUp(walletId, "200.00"); // enough for exactly 2 deductions

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures  = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "CONCURRENT-ORDER-" + i;
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    int status = mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                                    .header("Authorization", "Bearer " + adminToken)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) successes.incrementAndGet();
                    else              failures.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }

        startGate.countDown(); // release all threads simultaneously
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // Balance must be exactly ₹0 — two succeeded, three failed
        String balanceJson = mockMvc.perform(get("/wallets/{id}/balance", walletId)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        ApiResponse<BalanceResponse> br = objectMapper.readValue(balanceJson,
                objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, BalanceResponse.class));

        assertThat(br.getData().getBalancePaise()).isGreaterThanOrEqualTo(0L);
        assertThat(successes.get()).isEqualTo(2);
        assertThat(failures.get()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Hard case 4: Missing Idempotency-Key header returns 400
    // -------------------------------------------------------------------------

    @Test
    void deduct_returns400_whenIdempotencyKeyMissing() throws Exception {
        String walletId = createWallet();
        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                       .header("Authorization", "Bearer " + adminToken)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("{}"))
               .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Hard case 5: Wallet not found
    // -------------------------------------------------------------------------

    @Test
    void getBalance_returns404_forUnknownWallet() throws Exception {
        mockMvc.perform(get("/wallets/nonexistent-id/balance")
                       .header("Authorization", "Bearer " + adminToken))
               .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createWallet() throws Exception {
        CreateWalletRequest req = new CreateWalletRequest();
        req.setCustomerId("CUST-" + UUID.randomUUID());
        req.setName("Integration Test User");
        req.setEmail("test@keychain.com");
        req.setPhone("+919999999999");

        MvcResult result = mockMvc.perform(post("/wallets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        ApiResponse<WalletResponse> resp = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, WalletResponse.class));
        return resp.getData().getWalletId();
    }

    private void topUp(String walletId, String amountInr) throws Exception {
        TopUpRequest req = new TopUpRequest();
        req.setAmount(new BigDecimal(amountInr));

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                       .header("Authorization", "Bearer " + adminToken)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(req)))
               .andExpect(status().isOk());
    }

    private String extractTransactionId(String json) throws Exception {
        return objectMapper.readTree(json).path("data").path("transactionId").asText();
    }
}
