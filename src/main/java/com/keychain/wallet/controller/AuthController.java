package com.keychain.wallet.controller;

import com.keychain.wallet.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Issues JWT tokens for testing. In production this would be a dedicated
 * identity service (OAuth2 / SSO) — not part of the wallet service itself.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtil jwtUtil;

    @PostMapping("/token")
    public Map<String, String> token(@RequestBody Map<String, String> body) {
        String customerId = body.getOrDefault("customerId", "anonymous");
        String role = body.getOrDefault("role", "CUSTOMER").toUpperCase();

        if (!role.equals("CUSTOMER") && !role.equals("SERVICE") && !role.equals("ADMIN")) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }

        return Map.of("token", jwtUtil.generate(customerId, role));
    }
}
