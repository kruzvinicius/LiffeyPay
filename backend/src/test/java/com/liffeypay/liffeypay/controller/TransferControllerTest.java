package com.liffeypay.liffeypay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liffeypay.liffeypay.dto.TransferByEmailRequest;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.TransferResponse;
import com.liffeypay.liffeypay.exception.DuplicateTransferException;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import com.liffeypay.liffeypay.config.SecurityConfig;
import com.liffeypay.liffeypay.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import(SecurityConfig.class)
class TransferControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean TransferService transferService;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean UserDetailsService userDetailsService;

    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TX_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private TransferResponse mockResponse() {
        return new TransferResponse(TX_ID, SOURCE_ID, TARGET_ID,
                new BigDecimal("30.0000"), "EUR", "COMPLETED", Instant.now());
    }

    @Test
    void transfer_happyPath_returns201WithTransferResponse() throws Exception {
        when(transferService.transfer(any(), isNull())).thenReturn(mockResponse());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("30.0000")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(TX_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void transfer_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("10.0000")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void transfer_missingSourceWalletId_returns400WithFieldError() throws Exception {
        String body = """
                {"targetWalletId":"%s","amount":"30.0000"}
                """.formatted(TARGET_ID);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.sourceWalletId").exists());
    }

    @Test
    void transfer_missingTargetWalletId_returns400WithFieldError() throws Exception {
        String body = """
                {"sourceWalletId":"%s","amount":"30.0000"}
                """.formatted(SOURCE_ID);

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.targetWalletId").exists());
    }

    @Test
    void transfer_zeroAmount_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("0.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.amount").exists());
    }

    @Test
    void transfer_insufficientFunds_returns422() throws Exception {
        when(transferService.transfer(any(), any()))
                .thenThrow(new InsufficientFundsException(
                        SOURCE_ID, new BigDecimal("10.0000"), new BigDecimal("50.0000")));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("50.0000")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void transfer_walletNotFound_returns404() throws Exception {
        when(transferService.transfer(any(), any()))
                .thenThrow(new ResourceNotFoundException("Wallet not found: " + SOURCE_ID));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("10.0000")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void transfer_duplicateIdempotencyKey_returns409() throws Exception {
        when(transferService.transfer(any(), eq("dup-key-123")))
                .thenThrow(new DuplicateTransferException("dup-key-123"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "dup-key-123")
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("10.0000")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void transfer_withIdempotencyKeyHeader_passesKeyToService() throws Exception {
        String key = "unique-key-abc";
        when(transferService.transfer(any(), eq(key))).thenReturn(mockResponse());

        mockMvc.perform(post("/api/v1/transfers")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal("10.0000")))))
                .andExpect(status().isCreated());

        verify(transferService).transfer(any(), eq(key));
    }

    @Test
    void transferByEmail_authenticated_returns201() throws Exception {
        when(transferService.transferByEmail(
                eq("sender@test.com"), eq("bob@example.com"), any(), isNull()))
            .thenReturn(mockResponse());

        mockMvc.perform(post("/api/v1/transfers/email")
                .with(jwt().jwt(j -> j.subject("sender@test.com")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TransferByEmailRequest("bob@example.com", new BigDecimal("30.00")))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.transactionId").value(TX_ID.toString()));
    }

    @Test
    void transferByEmail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/transfers/email")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new TransferByEmailRequest("bob@example.com", new BigDecimal("30.00")))))
            .andExpect(status().isUnauthorized());
    }
}