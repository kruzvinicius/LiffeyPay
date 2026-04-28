package com.liffeypay.liffeypay.integration;

import com.liffeypay.liffeypay.dto.DepositRequest;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.WithdrawalRequest;
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

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_filterByDeposit_returnsOnlyDeposits() {
        String aliceJwt = registerAndLogin("fd_alice@test.com", "password123");
        String bobJwt   = registerAndLogin("fd_bob@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        // Creates a DEPOSIT transaction for Alice
        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

        // Creates a SENT (TRANSFER) transaction for Alice
        wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
        TransferRequest req = new TransferRequest(aliceWallet, bobWallet, new BigDecimal("10.00"));
        restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, withAuth(req, aliceJwt), Map.class);
        wireMock.resetAll();

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions?type=DEPOSIT",
            HttpMethod.GET, withAuth(aliceJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> content = extractContent(response);
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("DEPOSIT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_filterBySent_returnsOnlySentTransfers() {
        String aliceJwt = registerAndLogin("fs_alice@test.com", "password123");
        String bobJwt   = registerAndLogin("fs_bob@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        // Deposit so Alice has funds, then transfer (creates DEPOSIT + SENT)
        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

        wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
        TransferRequest req = new TransferRequest(aliceWallet, bobWallet, new BigDecimal("10.00"));
        restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, withAuth(req, aliceJwt), Map.class);
        wireMock.resetAll();

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions?type=SENT",
            HttpMethod.GET, withAuth(aliceJwt), Map.class);

        List<Map<String, Object>> content = extractContent(response);
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("SENT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_filterByReceived_returnsOnlyReceivedTransfers() {
        String aliceJwt = registerAndLogin("fr_alice@test.com", "password123");
        String bobJwt   = registerAndLogin("fr_bob@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);
        UUID bobWallet   = getWalletId(bobJwt);

        // Alice deposits and sends to Bob; query Bob's wallet for RECEIVED
        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

        wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
        TransferRequest req = new TransferRequest(aliceWallet, bobWallet, new BigDecimal("10.00"));
        restTemplate.exchange("/api/v1/transfers", HttpMethod.POST, withAuth(req, aliceJwt), Map.class);
        wireMock.resetAll();

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + bobWallet + "/transactions?type=RECEIVED",
            HttpMethod.GET, withAuth(bobJwt), Map.class);

        List<Map<String, Object>> content = extractContent(response);
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("RECEIVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_filterByWithdrawal_returnsOnlyWithdrawals() {
        String aliceJwt = registerAndLogin("fw_alice@test.com", "password123");
        UUID aliceWallet = getWalletId(aliceJwt);

        // Deposit then withdraw (creates DEPOSIT + WITHDRAWAL)
        restTemplate.exchange("/api/v1/wallets/me/deposit", HttpMethod.POST,
            withAuth(new DepositRequest(new BigDecimal("100.00")), aliceJwt), Map.class);

        wireMock.stubFor(get(urlEqualTo("/authorize")).willReturn(okJson(AUTHORIZED_BODY)));
        restTemplate.exchange("/api/v1/wallets/me/withdraw", HttpMethod.POST,
            withAuth(new WithdrawalRequest(new BigDecimal("30.00")), aliceJwt), Map.class);
        wireMock.resetAll();

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + aliceWallet + "/transactions?type=WITHDRAWAL",
            HttpMethod.GET, withAuth(aliceJwt), Map.class);

        List<Map<String, Object>> content = extractContent(response);
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type")).isEqualTo("WITHDRAWAL");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHistory_invalidType_returns400() {
        String jwt = registerAndLogin("iv_user@test.com", "password123");
        UUID walletId = getWalletId(jwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/wallets/" + walletId + "/transactions?type=INVALID",
            HttpMethod.GET, withAuth(jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<String, Object>) response.getBody()).get("success")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractContent(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return (List<Map<String, Object>>) data.get("content");
    }
}
