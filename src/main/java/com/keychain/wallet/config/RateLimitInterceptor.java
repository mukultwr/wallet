package com.keychain.wallet.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Distributed token-bucket rate limiter via Redisson. Limits are per-wallet for write/read
 * endpoints and per-IP for wallet creation. All thresholds are configurable in application.yml.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedissonClient redissonClient;

    // Limits are externalized — change in application.yml without a code deploy
    @Value("${rate-limit.deduct.requests:20}")
    private long deductRequests;

    @Value("${rate-limit.deduct.per-seconds:60}")
    private long deductPerSeconds;

    @Value("${rate-limit.topup.requests:10}")
    private long topupRequests;

    @Value("${rate-limit.topup.per-seconds:60}")
    private long topupPerSeconds;

    @Value("${rate-limit.read.requests:60}")
    private long readRequests;

    @Value("${rate-limit.read.per-seconds:60}")
    private long readPerSeconds;

    @Value("${rate-limit.create.requests:10}")
    private long createRequests;

    @Value("${rate-limit.create.per-seconds:60}")
    private long createPerSeconds;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String path   = request.getRequestURI();
        String method = request.getMethod();
        String key;
        long   rate;
        long   perSeconds;

        if (method.equals("POST") && path.matches("/wallets/[^/]+/deduct")) {
            String walletId = extractWalletId(path);
            key        = "rl:deduct:" + walletId;
            rate       = deductRequests;
            perSeconds = deductPerSeconds;

        } else if (method.equals("POST") && path.matches("/wallets/[^/]+/topup")) {
            String walletId = extractWalletId(path);
            key        = "rl:topup:" + walletId;
            rate       = topupRequests;
            perSeconds = topupPerSeconds;

        } else if (method.equals("POST") && path.equals("/wallets")) {
            // Per-IP to prevent mass wallet creation
            String ip  = getClientIp(request);
            key        = "rl:create:" + ip;
            rate       = createRequests;
            perSeconds = createPerSeconds;

        } else if (method.equals("GET") && path.matches("/wallets/[^/]+/(balance|transactions)")) {
            String walletId = extractWalletId(path);
            key        = "rl:read:" + walletId;
            rate       = readRequests;
            perSeconds = readPerSeconds;

        } else {
            return true; // actuator, unknown paths — no limit
        }

        if (!tryAcquire(key, rate, perSeconds)) {
            log.warn("Rate limit exceeded: key={} limit={} per={}s", key, rate, perSeconds);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"success\":false,\"error\":\"Rate limit exceeded — too many requests. Please slow down.\"}"
            );
            return false;
        }

        return true;
    }

    // Uses Redisson's distributed token bucket — correct across all service instances.
    // trySetRate is idempotent: if the limiter already exists in Redis, it's reused.
    private boolean tryAcquire(String key, long rate, long perSeconds) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, rate, perSeconds, RateIntervalUnit.SECONDS);
        return limiter.tryAcquire();
    }

    private String extractWalletId(String path) {
        String[] parts = path.split("/");
        return parts.length >= 3 ? parts[2] : "unknown";
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
