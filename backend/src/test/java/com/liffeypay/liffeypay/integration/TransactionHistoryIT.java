package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionHistoryIT extends IntegrationTestBase {

    private static final String AUTHORIZED_BODY = "{\"status\":\"AUTHORIZED\"}";

    private void executeTransfer(UUID sourceWalletId, UUID targetWalletId,
                                 BigDecimal amount, String jwt) {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));
        TransferRequest req = new TransferRequest(sourceWalletId, targetWalletId, amount);
        restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, jwt), Map.class);
        wireMock.resetAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_returnsSentAndReceived() {
        String aliceJwt = registerAndLogin("alice@test.com", "password123");
        String bobJwt   = registerAndLogin("bob@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", aliceWallet);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", bobWallet);

        executeTransfer(aliceWallet, bobWallet, new BigDecimal("20.00"), aliceJwt);
        executeTransfer(bobWallet, aliceWallet, new BigDecimal("10.00"), bobJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions",
            HttpMethod.GET, withAuth(aliceJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        List<Map<String, Object>> content =
            (List<Map<String, Object>>) data.get("content");
        assertThat(content).hasSize(2);

        List<String> types = content.stream()
            .map(tx -> (String) tx.get("type"))
            .toList();
        assertThat(types).containsExactlyInAnyOrder("SENT", "RECEIVED");
    }

    @Test
    void getHistory_nonOwner_returns404() {
        String ownerJwt  = registerAndLogin("owner3@test.com", "password123");
        String hackerJwt = registerAndLogin("hacker2@test.com", "password123");
        UUID ownerWallet = getWalletId(ownerJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + ownerWallet + "/transactions",
            HttpMethod.GET, withAuth(hackerJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_pagination_respectsPageSize() {
        String aliceJwt = registerAndLogin("alice2@test.com", "password123");
        String bobJwt   = registerAndLogin("bob2@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", aliceWallet);
        executeTransfer(aliceWallet, bobWallet, new BigDecimal("10.00"), aliceJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", aliceWallet);
        executeTransfer(aliceWallet, bobWallet, new BigDecimal("5.00"), aliceJwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions?page=0&size=1",
            HttpMethod.GET, withAuth(aliceJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        List<?> content = (List<?>) data.get("content");
        assertThat(content).hasSize(1);
        assertThat(((Number) data.get("totalElements")).longValue()).isEqualTo(2L);
    }
}
