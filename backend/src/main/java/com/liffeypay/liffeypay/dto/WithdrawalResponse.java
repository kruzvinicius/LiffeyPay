package com.liffeypay.liffeypay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalResponse(
    UUID id,
    UUID walletId,
    BigDecimal amount,
    String currency,
    Instant createdAt
) {}
