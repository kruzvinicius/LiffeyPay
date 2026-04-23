package com.liffeypay.liffeypay.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.dto.LoginRequest;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    static final WireMockServer wireMock =
        new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.authorization.url",
            () -> wireMock.baseUrl() + "/authorize");
        registry.add("app.notification.url",
            () -> wireMock.baseUrl() + "/notify");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @LocalServerPort
    protected int port;

    @BeforeEach
    void cleanUp() {
        wireMock.resetAll();
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM wallets");
        jdbcTemplate.execute("DELETE FROM users");
    }

    @SuppressWarnings("unchecked")
    protected String registerAndLogin(String email, String password) {
        RegisterUserRequest reg = new RegisterUserRequest(
            "Test User", email,
            UUID.randomUUID().toString().replace("-", "").substring(0, 11),
            password, UserType.INDIVIDUAL, "EUR"
        );
        restTemplate.postForEntity("/api/v1/users", reg, Map.class);

        var response = restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, password), Map.class);
        var data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return (String) data.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    protected String registerAndLoginAsMerchant(String email, String password) {
        RegisterUserRequest reg = new RegisterUserRequest(
            "Merchant", email,
            UUID.randomUUID().toString().replace("-", "").substring(0, 11),
            password, UserType.MERCHANT, "EUR"
        );
        restTemplate.postForEntity("/api/v1/users", reg, Map.class);

        var response = restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, password), Map.class);
        var data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return (String) data.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    protected UUID getWalletId(String jwt) {
        var response = restTemplate.exchange(
            "/api/v1/wallets/me", HttpMethod.GET, withAuth(jwt), Map.class);
        var data = (Map<String, Object>)
            ((Map<String, Object>) response.getBody()).get("data");
        return UUID.fromString((String) data.get("walletId"));
    }

    protected HttpEntity<?> withAuth(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return new HttpEntity<>(headers);
    }

    protected <T> HttpEntity<T> withAuth(T body, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return new HttpEntity<>(body, headers);
    }
}
