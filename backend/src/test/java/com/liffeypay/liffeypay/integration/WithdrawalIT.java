package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.dto.DepositRequest;
import com.liffeypay.liffeypay.dto.WithdrawalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalIT extends IntegrationTestBase {

    private static final String AUTHORIZED_BODY = "{\"status\":\"AUTHORIZED\"}";

    @Test
    @SuppressWarnings("unchecked")
    void withdraw_happyPath_balanceDecreasedInDb() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("wd1@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("40.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) response.getBody()).get("data");
        assertThat(data.get("walletId")).isEqualTo(walletId.toString());

        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("60.0000");
    }

    @Test
    void withdraw_insufficientFunds_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("wd2@test.com", "password123");

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("50.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withdraw_idempotency_secondCallReturnsCached() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("wd3@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.set("Idempotency-Key", "withdraw-idem-key-001");
        HttpEntity<WithdrawalRequest> entity =
            new HttpEntity<>(new WithdrawalRequest(new BigDecimal("30.00")), headers);

        ResponseEntity<Map> first  = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST, entity, Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST, entity, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> firstData  = (Map<String, Object>) ((Map<String, Object>) first.getBody()).get("data");
        Map<String, Object> secondData = (Map<String, Object>) ((Map<String, Object>) second.getBody()).get("data");
        assertThat(firstData.get("id")).isEqualTo(secondData.get("id"));

        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("70.0000");
    }

    @Test
    void withdraw_authorizerDenies_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        String jwt = registerAndLogin("wd4@test.com", "password123");
        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), jwt), Map.class);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("50.00")), jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        UUID walletId = getWalletId(jwt);
        BigDecimal balance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, walletId);
        assertThat(balance).isEqualByComparingTo("100.0000");
    }

    @Test
    void withdraw_noToken_returns401() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/wallets/me/withdraw",
            new WithdrawalRequest(new BigDecimal("50.00")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
