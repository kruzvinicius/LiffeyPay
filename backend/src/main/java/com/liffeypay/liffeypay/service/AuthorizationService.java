package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.dto.TransferRequest;

public interface AuthorizationService {
    void authorize(TransferRequest request);
}
