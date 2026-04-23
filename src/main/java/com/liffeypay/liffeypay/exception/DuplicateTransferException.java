package com.liffeypay.liffeypay.exception;

public class DuplicateTransferException extends BusinessException {
    public DuplicateTransferException(String idempotencyKey) {
        super(String.format("Transfer with idempotency key '%s' already processed", idempotencyKey));
    }
}
