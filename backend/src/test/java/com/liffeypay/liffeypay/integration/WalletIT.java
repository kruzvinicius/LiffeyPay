package com.liffeypay.liffeypay.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletIT extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void getMyWallet_authenticated_returnsWallet() {
        String jwt = registerAndLogin("wallet@test.com", "password123");

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me", HttpMethod.GET, withAuth(jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isNotNull();
        assertThat(data.get("currency")).isEqualTo("EUR");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getById_owner_returnsWallet() {
        String jwt = registerAndLogin("owner@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + walletId, HttpMethod.GET, withAuth(jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isEqualTo(walletId.toString());
    }

    @Test
    void getById_nonOwner_returns404() {
        String ownerJwt  = registerAndLogin("owner2@test.com", "password123");
        String hackerJwt = registerAndLogin("hacker@test.com", "password123");
        UUID ownerWalletId = getWalletId(ownerJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + ownerWalletId, HttpMethod.GET, withAuth(hackerJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMyWallet_noToken_returns401() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            "/api/v1/wallets/me", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}