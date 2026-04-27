package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public Page<TransactionResponse> getTransactions(
            UUID walletId, String requestingEmail, Pageable pageable) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getEmail().equals(requestingEmail)) {
            throw new ResourceNotFoundException("Wallet not found: " + walletId);
        }
        return transactionRepository
            .findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable)
            .map(t -> toResponse(t, walletId));
    }

    private TransactionResponse toResponse(Transaction t, UUID walletId) {
        if (t.getType() == TransactionType.DEPOSIT) {
            return new TransactionResponse(t.getId(), "DEPOSIT", null,
                t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt());
        }
        boolean sent = t.getSourceWallet().getId().equals(walletId);
        UUID counterpart = sent ? t.getTargetWallet().getId() : t.getSourceWallet().getId();
        return new TransactionResponse(
            t.getId(), sent ? "SENT" : "RECEIVED", counterpart,
            t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt()
        );
    }
}
