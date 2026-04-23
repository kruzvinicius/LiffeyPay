package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.config.SecurityConfig;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import com.liffeypay.liffeypay.service.TransactionService;
import com.liffeypay.liffeypay.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
@Import(SecurityConfig.class)
class WalletControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean WalletService walletService;
    @MockBean TransactionService transactionService;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean UserDetailsService userDetailsService;

    private static final UUID WALLET_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String OWNER_EMAIL = "owner@test.com";

    private WalletResponse mockWallet() {
        return new WalletResponse(WALLET_ID, USER_ID, new BigDecimal("500.0000"), "EUR", Instant.now());
    }

    @Test
    void getMyWallet_authenticated_returns200() throws Exception {
        when(walletService.getByOwnerEmail(OWNER_EMAIL)).thenReturn(mockWallet());

        mockMvc.perform(get("/api/v1/wallets/me")
                        .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.walletId").value(WALLET_ID.toString()))
                .andExpect(jsonPath("$.data.currency").value("EUR"));
    }

    @Test
    void getMyWallet_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_ownerAccess_returns200() throws Exception {
        when(walletService.getById(WALLET_ID, OWNER_EMAIL)).thenReturn(mockWallet());

        mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID)
                        .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.walletId").value(WALLET_ID.toString()));
    }

    @Test
    void getById_nonOwnerAccess_returns404() throws Exception {
        when(walletService.getById(eq(WALLET_ID), eq("hacker@evil.com")))
                .thenThrow(new ResourceNotFoundException("Wallet not found: " + WALLET_ID));

        mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID)
                        .with(jwt().jwt(j -> j.subject("hacker@evil.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getById_walletNotFound_returns404() throws Exception {
        when(walletService.getById(any(), eq(OWNER_EMAIL)))
                .thenThrow(new ResourceNotFoundException("Wallet not found: " + WALLET_ID));

        mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID)
                        .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTransactions_owner_returns200WithPage() throws Exception {
        TransactionResponse tx = new TransactionResponse(
            UUID.randomUUID(), "SENT", UUID.randomUUID(),
            new BigDecimal("50.0000"), "EUR", "COMPLETED", Instant.now()
        );
        Page<TransactionResponse> page = new PageImpl<>(List.of(tx));
        when(transactionService.getTransactions(eq(WALLET_ID), eq(OWNER_EMAIL), any()))
            .thenReturn(page);

        mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions")
                        .with(jwt().jwt(j -> j.subject(OWNER_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].type").value("SENT"));
    }

    @Test
    void getTransactions_nonOwner_returns404() throws Exception {
        when(transactionService.getTransactions(eq(WALLET_ID), eq("hacker@evil.com"), any()))
            .thenThrow(new ResourceNotFoundException("Wallet not found: " + WALLET_ID));

        mockMvc.perform(get("/api/v1/wallets/" + WALLET_ID + "/transactions")
                        .with(jwt().jwt(j -> j.subject("hacker@evil.com"))))
                .andExpect(status().isNotFound());
    }
}
