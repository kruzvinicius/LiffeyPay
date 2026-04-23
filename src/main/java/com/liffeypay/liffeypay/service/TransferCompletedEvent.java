package com.liffeypay.liffeypay.service;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCompletedEvent(
    UUID transactionId,
    UUID sourceWalletId,
    UUID targetWalletId,
    BigDecimal amount,
    String currency
) {}
