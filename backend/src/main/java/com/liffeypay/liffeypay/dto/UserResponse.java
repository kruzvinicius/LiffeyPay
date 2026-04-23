package com.liffeypay.liffeypay.dto;

import java.util.UUID;

public record UserResponse(UUID userId, String fullName, String email, UUID walletId) {}
