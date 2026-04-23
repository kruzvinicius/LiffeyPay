package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletResponse getById(UUID walletId, String requestingEmail) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getEmail().equals(requestingEmail)) {
            // Return 404 to avoid leaking wallet existence to non-owners
            throw new ResourceNotFoundException("Wallet not found: " + walletId);
        }
        return toResponse(wallet);
    }

    public WalletResponse getByOwnerEmail(String email) {
        Wallet wallet = walletRepository.findByUserEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for: " + email));
        return toResponse(wallet);
    }

    private WalletResponse toResponse(Wallet w) {
        return new WalletResponse(
            w.getId(),
            w.getUser().getId(),
            w.getBalance(),
            w.getCurrency(),
            w.getUpdatedAt()
        );
    }
}
