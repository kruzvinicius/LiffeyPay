package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Service
@Slf4j
public class HttpAuthorizationService implements AuthorizationService {

    private final RestClient restClient;

    public HttpAuthorizationService(
        @Value("${app.authorization.url}") String url,
        @Value("${app.authorization.timeout-ms:3000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public void authorize(TransferRequest request) {
        try {
            AuthorizationResponse response = restClient.get()
                .retrieve()
                .body(AuthorizationResponse.class);
            if (response == null || !"AUTHORIZED".equalsIgnoreCase(response.status())) {
                throw new TransferNotAuthorizedException("Transfer denied by authorization service");
            }
        } catch (TransferNotAuthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Authorization service error: {}", e.getMessage());
            throw new TransferNotAuthorizedException("Authorization service unavailable");
        }
    }

    private record AuthorizationResponse(String status) {}
}
