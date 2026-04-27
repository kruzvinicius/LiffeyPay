package com.liffeypay.liffeypay.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferByEmailRequest(

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Invalid email format")
    String recipientEmail,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    @Digits(integer = 15, fraction = 4, message = "Invalid monetary amount format")
    BigDecimal amount
) {}
