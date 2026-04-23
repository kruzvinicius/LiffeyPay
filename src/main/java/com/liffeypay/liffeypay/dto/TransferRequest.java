package com.liffeypay.liffeypay.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(

    @NotNull(message = "Source wallet ID is required")
    UUID sourceWalletId,

    @NotNull(message = "Target wallet ID is required")
    UUID targetWalletId,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Invalid monetary amount format")
    BigDecimal amount
) {}
