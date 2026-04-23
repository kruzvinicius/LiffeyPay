package com.liffeypay.liffeypay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
    UUID transactionId,
    UUID sourceWalletId,
    UUID targetWalletId,
    BigDecimal amount,
    String currency,
    String status,
    Instant createdAt
) {}
