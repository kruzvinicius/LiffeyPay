package com.liffeypay.liffeypay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liffeypay.liffeypay.config.SecurityConfig;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import com.liffeypay.liffeypay.dto.UserResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import com.liffeypay.liffeypay.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean UserService userService;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean UserDetailsService userDetailsService;

    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WALLET_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private RegisterUserRequest validRequest() {
        return new RegisterUserRequest(
                "Alice Smith", "alice@test.com", "12345678901",
                "password123", UserType.INDIVIDUAL, "EUR");
    }

    @Test
    void register_validRequest_returns201WithUserResponse() throws Exception {
        when(userService.register(any()))
                .thenReturn(new UserResponse(USER_ID, "Alice Smith", "alice@test.com", WALLET_ID));

        mockMvc.perform(post("/api/v1/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("alice@test.com"))
                .andExpect(jsonPath("$.data.walletId").value(WALLET_ID.toString()));
    }

    @Test
    void register_duplicateEmail_returns400() throws Exception {
        when(userService.register(any()))
                .thenThrow(new BusinessException("Email already registered: alice@test.com"));

        mockMvc.perform(post("/api/v1/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email already registered: alice@test.com"));
    }

    @Test
    void register_missingFullName_returns400WithFieldError() throws Exception {
        String body = """
                {"email":"alice@test.com","documentNumber":"123","password":"password123","userType":"INDIVIDUAL"}
                """;

        mockMvc.perform(post("/api/v1/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fullName").exists());
    }

    @Test
    void register_passwordTooShort_returns400WithFieldError() throws Exception {
        RegisterUserRequest req = new RegisterUserRequest(
                "Alice Smith", "alice@test.com", "12345678901",
                "short", UserType.INDIVIDUAL, "EUR");

        mockMvc.perform(post("/api/v1/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.password").exists());
    }

    @Test
    void register_isPublicEndpoint_noJwtRequired() throws Exception {
        when(userService.register(any()))
                .thenReturn(new UserResponse(USER_ID, "Alice Smith", "alice@test.com", WALLET_ID));

        mockMvc.perform(post("/api/v1/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());
    }
}