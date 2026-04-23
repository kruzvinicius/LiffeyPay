package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/me")
    public ApiResponse<WalletResponse> getMyWallet(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(walletService.getByOwnerEmail(jwt.getSubject()));
    }

    @GetMapping("/{walletId}")
    public ApiResponse<WalletResponse> getById(
            @PathVariable UUID walletId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(walletService.getById(walletId, jwt.getSubject()));
    }
}
