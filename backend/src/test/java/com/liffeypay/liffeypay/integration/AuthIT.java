package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.dto.LoginRequest;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void register_createsUserAndWallet() {
        RegisterUserRequest req = new RegisterUserRequest(
            "Vinicius Cruz", "vinicius@test.com", "12345678901",
            "password123", UserType.INDIVIDUAL, "EUR"
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/users", req, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isNotNull();
        Long walletCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM wallets WHERE user_id = " +
            "(SELECT id FROM users WHERE email = ?)",
            Long.class, "vinicius@test.com");
        assertThat(walletCount).isEqualTo(1L);
    }

    @Test
    void login_validCredentials_returnsJwt() {
        String jwt = registerAndLogin("user@test.com", "password123");
        assertThat(jwt).isNotBlank();
    }

    @Test
    void login_wrongPassword_returns401() {
        registerAndLogin("user@test.com", "password123");

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("user@test.com", "wrongpass"),
            Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_duplicateEmail_returns400() {
        RegisterUserRequest first = new RegisterUserRequest(
            "User One", "dup@test.com", "11122233344",
            "password123", UserType.INDIVIDUAL, "EUR"
        );
        restTemplate.postForEntity("/api/v1/users", first, Map.class);

        RegisterUserRequest second = new RegisterUserRequest(
            "User Two", "dup@test.com", "99988877766",
            "password123", UserType.INDIVIDUAL, "EUR"
        );
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/users", second, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}