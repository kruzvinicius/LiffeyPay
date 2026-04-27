package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionStatus;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.DepositResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
public class DepositService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public DepositResponse deposit(String ownerEmail, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> executeDeposit(ownerEmail, amount, idempotencyKey));
        }
        return executeDeposit(ownerEmail, amount, null);
    }

    private DepositResponse executeDeposit(String ownerEmail, BigDecimal amount, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserEmailWithLock(ownerEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for: " + ownerEmail));

        wallet.setBalance(wallet.getBalance().add(amount));

        Transaction saved = transactionRepository.save(Transaction.builder()
            .type(TransactionType.DEPOSIT)
            .targetWallet(wallet)
            .amount(amount)
            .currency(wallet.getCurrency())
            .status(TransactionStatus.COMPLETED)
            .idempotencyKey(idempotencyKey)
            .build());

        return toResponse(saved);
    }

    private DepositResponse toResponse(Transaction t) {
        return new DepositResponse(
            t.getId(),
            t.getTargetWallet().getId(),
            t.getAmount(),
            t.getCurrency(),
            t.getCreatedAt()
        );
    }
}
