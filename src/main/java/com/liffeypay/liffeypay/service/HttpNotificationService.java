package com.liffeypay.liffeypay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class HttpNotificationService implements NotificationService {

    private final RestClient restClient;

    public HttpNotificationService(@Value("${app.notification.url}") String url) {
        this.restClient = RestClient.builder().baseUrl(url).build();
    }

    @Override
    public void notify(TransferCompletedEvent event) {
        try {
            restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to notify for transaction {}: {}", event.transactionId(), e.getMessage());
        }
    }
}
