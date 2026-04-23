package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionStatus;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.TransferResponse;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> executeTransfer(request, idempotencyKey));
        }
        return executeTransfer(request, null);
    }

    private TransferResponse executeTransfer(TransferRequest request, String idempotencyKey) {
        // Acquire DB locks in consistent UUID order to prevent deadlocks under concurrency
        boolean sourceFirst =
            request.sourceWalletId().compareTo(request.targetWalletId()) <= 0;

        UUID firstId  = sourceFirst ? request.sourceWalletId() : request.targetWalletId();
        UUID secondId = sourceFirst ? request.targetWalletId() : request.sourceWalletId();

        Wallet first = walletRepository.findByIdWithLock(firstId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + firstId));
        Wallet second = walletRepository.findByIdWithLock(secondId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + secondId));

        Wallet source = sourceFirst ? first  : second;
        Wallet target = sourceFirst ? second : first;

        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                source.getId(), source.getBalance(), request.amount()
            );
        }

        source.setBalance(source.getBalance().subtract(request.amount()));
        target.setBalance(target.getBalance().add(request.amount()));

        Transaction transaction = Transaction.builder()
            .sourceWallet(source)
            .targetWallet(target)
            .amount(request.amount())
            .currency(source.getCurrency())
            .status(TransactionStatus.COMPLETED)
            .idempotencyKey(idempotencyKey)
            .build();

        Transaction saved = transactionRepository.save(transaction);

        log.info("Transfer completed [txId={}] {} -> {} amount={} {}",
            saved.getId(), source.getId(), target.getId(),
            request.amount(), source.getCurrency());

        return toResponse(saved);
    }

    private TransferResponse toResponse(Transaction t) {
        return new TransferResponse(
            t.getId(),
            t.getSourceWallet().getId(),
            t.getTargetWallet().getId(),
            t.getAmount(),
            t.getCurrency(),
            t.getStatus().name(),
            t.getCreatedAt()
        );
    }
}
