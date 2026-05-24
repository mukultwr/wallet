package com.keychain.wallet.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Health check — open
                .requestMatchers("/actuator/**").permitAll()
                // Token generation endpoint — open
                .requestMatchers("/auth/token").permitAll()
                // Wallet creation — ADMIN only
                .requestMatchers(HttpMethod.POST, "/wallets").hasRole("ADMIN")
                // Topup and deduct — SERVICE or ADMIN (Order Service uses SERVICE token)
                .requestMatchers(HttpMethod.POST, "/wallets/*/topup").hasAnyRole("SERVICE", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/wallets/*/deduct").hasAnyRole("SERVICE", "ADMIN")
                // Read endpoints — CUSTOMER (own wallet only, checked in controller), SERVICE, ADMIN
                .requestMatchers(HttpMethod.GET, "/wallets/*/balance").hasAnyRole("CUSTOMER", "SERVICE", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/wallets/*/transactions").hasAnyRole("CUSTOMER", "SERVICE", "ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
