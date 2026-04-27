package com.liffeypay.liffeypay.exception;

public class SelfTransferException extends BusinessException {
    public SelfTransferException() {
        super("Self-transfer is not allowed");
    }
}
