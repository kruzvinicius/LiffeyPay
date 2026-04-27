package com.liffeypay.liffeypay.integration;

import com.github.tomakehurst.wiremock.http.Fault;
import com.liffeypay.liffeypay.dto.TransferByEmailRequest;
import com.liffeypay.liffeypay.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransferIT extends IntegrationTestBase {

    private static final String AUTHORIZED_BODY = "{\"status\":\"AUTHORIZED\"}";

    @Test
    @SuppressWarnings("unchecked")
    void transfer_happyPath_balancesUpdatedInDb() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("sender@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);

        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("30.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        BigDecimal sourceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, sourceWalletId);
        BigDecimal targetBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, targetWalletId);
        assertThat(sourceBalance).isEqualByComparingTo("70.0000");
        assertThat(targetBalance).isEqualByComparingTo("30.0000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transfer_idempotency_secondCallReturnsCachedResult() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("sender2@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver2@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("20.00"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(senderJwt);
        headers.set("Idempotency-Key", "unique-key-abc123");
        var entity = new HttpEntity<>(req, headers);

        ResponseEntity<Map> first = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, entity, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, entity, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> firstData  = (Map<String, Object>)
            ((Map<String, Object>) first.getBody()).get("data");
        Map<String, Object> secondData = (Map<String, Object>)
            ((Map<String, Object>) second.getBody()).get("data");
        assertThat(firstData.get("id")).isEqualTo(secondData.get("id"));

        BigDecimal sourceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, sourceWalletId);
        assertThat(sourceBalance).isEqualByComparingTo("80.0000");
    }

    @Test
    void transfer_merchantSource_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String merchantJwt  = registerAndLoginAsMerchant("merchant@test.com", "password123");
        String receiverJwt  = registerAndLogin("rcv@test.com", "password123");
        UUID merchantWallet = getWalletId(merchantJwt);
        UUID targetWallet   = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", merchantWallet);

        TransferRequest req = new TransferRequest(
            merchantWallet, targetWallet, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, merchantJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_insufficientFunds_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("broke@test.com", "password123");
        String receiverJwt = registerAndLogin("rcv2@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("50.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_authorizerDenies_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        String senderJwt   = registerAndLogin("sender3@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver3@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_authorizerDown_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        String senderJwt   = registerAndLogin("sender4@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver4@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void transfer_notificationFails_transferSucceeds() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));
        wireMock.stubFor(post(urlEqualTo("/notify"))
            .willReturn(aResponse().withStatus(500)));

        String senderJwt   = registerAndLogin("sender5@test.com", "password123");
        String receiverJwt = registerAndLogin("receiver5@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

        TransferRequest req = new TransferRequest(
            sourceWalletId, targetWalletId, new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void transferByEmail_happyPath_balancesUpdatedInDb() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String senderJwt   = registerAndLogin("emailsender@test.com", "password123");
        String receiverJwt = registerAndLogin("emailreceiver@test.com", "password123");
        UUID sourceWalletId = getWalletId(senderJwt);
        UUID targetWalletId = getWalletId(receiverJwt);
        jdbcTemplate.update("UPDATE wallets SET balance = 100.0000 WHERE id = ?", sourceWalletId);

        TransferByEmailRequest req = new TransferByEmailRequest("emailreceiver@test.com", new BigDecimal("40.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers/email", HttpMethod.POST, withAuth(req, senderJwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        BigDecimal sourceBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, sourceWalletId);
        BigDecimal targetBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM wallets WHERE id = ?", BigDecimal.class, targetWalletId);
        assertThat(sourceBalance).isEqualByComparingTo("60.0000");
        assertThat(targetBalance).isEqualByComparingTo("40.0000");
    }

    @Test
    void transferByEmail_selfTransfer_returns422() {
        wireMock.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson(AUTHORIZED_BODY)));

        String jwt = registerAndLogin("selfsender@test.com", "password123");
        TransferByEmailRequest req = new TransferByEmailRequest("selfsender@test.com", new BigDecimal("10.00"));

        ResponseEntity<Map> response = restTemplate.exchange(
            "/api/v1/transfers/email", HttpMethod.POST, withAuth(req, jwt), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
