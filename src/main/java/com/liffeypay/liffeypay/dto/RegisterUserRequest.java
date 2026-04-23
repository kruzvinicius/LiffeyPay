package com.liffeypay.liffeypay.dto;

import com.liffeypay.liffeypay.domain.model.UserType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterUserRequest(
    @NotBlank(message = "Full name is required") String fullName,
    @NotBlank @Email(message = "Invalid email format") String email,
    @NotBlank(message = "Document number is required") String documentNumber,
    @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
    @NotNull(message = "User type is required") UserType userType,
    String currency
) {}
