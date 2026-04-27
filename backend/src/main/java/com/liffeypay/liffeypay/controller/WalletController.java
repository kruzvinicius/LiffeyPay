package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.DepositRequest;
import com.liffeypay.liffeypay.dto.DepositResponse;
import com.liffeypay.liffeypay.dto.PageResponse;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.dto.WithdrawalRequest;
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.service.DepositService;
import com.liffeypay.liffeypay.service.TransactionService;
import com.liffeypay.liffeypay.service.WalletService;
import com.liffeypay.liffeypay.service.WithdrawalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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
    private final DepositService depositService;
    private final WithdrawalService withdrawalService;

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
    public ApiResponse<PageResponse<TransactionResponse>> getTransactions(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(
            transactionService.getTransactions(walletId, jwt.getSubject(), pageable)));
    }

    @PostMapping("/me/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DepositResponse> deposit(
            @Valid @RequestBody DepositRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(depositService.deposit(jwt.getSubject(), request.amount(), idempotencyKey));
    }

    @PostMapping("/me/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WithdrawalResponse> withdraw(
            @Valid @RequestBody WithdrawalRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(withdrawalService.withdraw(jwt.getSubject(), request.amount(), idempotencyKey));
    }
}
