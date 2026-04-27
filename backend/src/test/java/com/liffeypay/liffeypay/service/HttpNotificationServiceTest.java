package com.liffeypay.liffeypay.service;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatNoException;

class HttpNotificationServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private HttpNotificationService service;

    @BeforeEach
    void setUp() {
        service = new HttpNotificationService(wm.getRuntimeInfo().getHttpBaseUrl() + "/notify");
    }

    private TransferCompletedEvent event() {
        return new TransferCompletedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("100.00"), "EUR"
        );
    }

    @Test
    void notify_success_noExceptionThrown() {
        wm.stubFor(post(urlEqualTo("/notify")).willReturn(aResponse().withStatus(200)));

        assertThatNoException().isThrownBy(() -> service.notify(event()));
    }

    @Test
    void notify_serverError_swallowsSilently() {
        wm.stubFor(post(urlEqualTo("/notify")).willReturn(aResponse().withStatus(500)));

        assertThatNoException().isThrownBy(() -> service.notify(event()));
    }

    @Test
    void notify_connectionReset_swallowsSilently() {
        wm.stubFor(post(urlEqualTo("/notify"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatNoException().isThrownBy(() -> service.notify(event()));
    }
}
