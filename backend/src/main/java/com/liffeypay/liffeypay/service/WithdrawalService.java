package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionStatus;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
public class WithdrawalService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final AuthorizationService authorizationService;

    public WithdrawalResponse withdraw(String ownerEmail, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> executeWithdrawal(ownerEmail, amount, idempotencyKey));
        }
        return executeWithdrawal(ownerEmail, amount, null);
    }

    private WithdrawalResponse executeWithdrawal(String ownerEmail, BigDecimal amount, String idempotencyKey) {
        Wallet wallet = walletRepository.findByUserEmailWithLock(ownerEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for: " + ownerEmail));

        authorizationService.authorize();

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(wallet.getId(), wallet.getBalance(), amount);
        }

        wallet.setBalance(wallet.getBalance().subtract(amount));

        Transaction saved = transactionRepository.save(Transaction.builder()
            .type(TransactionType.WITHDRAWAL)
            .sourceWallet(wallet)
            .amount(amount)
            .currency(wallet.getCurrency())
            .status(TransactionStatus.COMPLETED)
            .idempotencyKey(idempotencyKey)
            .build());

        return toResponse(saved);
    }

    private WithdrawalResponse toResponse(Transaction t) {
        return new WithdrawalResponse(
            t.getId(),
            t.getSourceWallet().getId(),
            t.getAmount(),
            t.getCurrency(),
            t.getCreatedAt()
        );
    }
}
