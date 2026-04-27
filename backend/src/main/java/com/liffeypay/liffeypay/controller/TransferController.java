package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.TransferByEmailRequest;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.TransferResponse;
import com.liffeypay.liffeypay.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> transfer(
            @RequestBody @Valid TransferRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return ApiResponse.ok(transferService.transfer(request, idempotencyKey), "Transfer completed successfully");
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> transferByEmail(
            @RequestBody @Valid TransferByEmailRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(
                transferService.transferByEmail(jwt.getSubject(), request.recipientEmail(), request.amount(), idempotencyKey),
                "Transfer completed successfully");
    }
}
