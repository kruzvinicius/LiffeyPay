package com.liffeypay.liffeypay.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
    UUID walletId,
    UUID userId,
    BigDecimal balance,
    String currency,
    Instant updatedAt
) {}
