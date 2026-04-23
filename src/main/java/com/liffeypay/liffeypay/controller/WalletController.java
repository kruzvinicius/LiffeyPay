package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.service.TransactionService;
import com.liffeypay.liffeypay.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransactionService transactionService;

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

    @GetMapping("/{walletId}/transactions")
    public ApiResponse<Page<TransactionResponse>> getTransactions(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(transactionService.getTransactions(walletId, jwt.getSubject(), pageable));
    }
}
