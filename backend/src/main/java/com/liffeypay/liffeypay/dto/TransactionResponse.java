package com.liffeypay.liffeypay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID id,
    String type,
    UUID counterpartWalletId,
    BigDecimal amount,
    String currency,
    String status,
    Instant createdAt
) {}
