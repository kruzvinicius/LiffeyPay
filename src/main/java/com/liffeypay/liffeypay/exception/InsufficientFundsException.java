package com.liffeypay.liffeypay.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends BusinessException {
    public InsufficientFundsException(UUID walletId, BigDecimal available, BigDecimal requested) {
        super(String.format(
            "Insufficient funds in wallet %s. Available: %.4f, Requested: %.4f",
            walletId, available, requested
        ));
    }
}
