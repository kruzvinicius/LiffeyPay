package com.liffeypay.liffeypay.exception;

import java.util.UUID;

public class MerchantTransferNotAllowedException extends BusinessException {
    public MerchantTransferNotAllowedException(UUID walletId) {
        super("Merchant wallet cannot initiate transfers: " + walletId);
    }
}
