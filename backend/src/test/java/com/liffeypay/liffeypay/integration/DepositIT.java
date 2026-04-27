package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.dto.DepositRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DepositIT extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void deposit_happyPath_balanceIncreasedInDb() {
        String jwt = registerAndLogin("dep1@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isEqualTo(walletId.toString());

        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("100.0000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deposit_idempotency_secondCallReturnsCached() {
        String jwt = registerAndLogin("dep2@test.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.set("Idempotency-Key", "dep-idem-key-001");
        HttpEntity<DepositRequest> entity =
            new HttpEntity<>(new DepositRequest(new BigDecimal("50.00")), headers);

        ResponseEntity<Map> first  = restTemplate.exchange(
            "/api/v1/wallets/me/deposit", HttpMethod.POST, entity, Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
            "/api/v1/wallets/me/deposit", HttpMethod.POST, entity, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> firstData  = (Map<String, Object>) ((Map<String, Object>) first.getBody()).get("data");
        Map<String, Object> secondData = (Map<String, Object>) ((Map<String, Object>) second.getBody()).get("data");
        assertThat(firstData.get("id")).isEqualTo(secondData.get("id"));

        UUID walletId = getWalletId(jwt);
        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("50.0000");
    }

    @Test
    void deposit_noToken_returns401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/wallets/me/deposit",
            new DepositRequest(new BigDecimal("100.00")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deposit_appearsInTransactionHistory() {
        String jwt = registerAndLogin("dep3@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("75.00")), jwt), Map.class);

        ResponseEntity<Map> history = restTemplate.exchange(
            "/api/v1/wallets/" + walletId + "/transactions",
            HttpMethod.GET, withAuth(jwt), Map.class);

        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) history.getBody()).get("data");
        List<Map<String, Object>> content = (List<Map<String, Object>>) data.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("DEPOSIT");
        assertThat(content.get(0).get("counterpartWalletId")).isNull();
    }
}
