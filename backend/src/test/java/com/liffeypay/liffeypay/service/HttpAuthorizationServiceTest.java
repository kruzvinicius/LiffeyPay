package com.liffeypay.liffeypay.service;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpAuthorizationServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private HttpAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new HttpAuthorizationService(
            wm.getRuntimeInfo().getHttpBaseUrl() + "/authorize", 3000);
    }

    @Test
    void authorize_authorized_noExceptionThrown() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"AUTHORIZED\"}")));

        assertThatNoException().isThrownBy(() -> service.authorize());
    }

    @Test
    void authorize_denied_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(okJson("{\"status\":\"DENIED\"}")));

        assertThatThrownBy(() -> service.authorize())
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void authorize_serverError_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.authorize())
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void authorize_connectionReset_throwsTransferNotAuthorizedException() {
        wm.stubFor(get(urlEqualTo("/authorize"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> service.authorize())
            .isInstanceOf(TransferNotAuthorizedException.class);
    }
}
