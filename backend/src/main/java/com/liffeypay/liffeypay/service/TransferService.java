package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionStatus;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.TransferResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.MerchantTransferNotAllowedException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import com.liffeypay.liffeypay.exception.SelfTransferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final AuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> executeTransfer(request, idempotencyKey));
        }
        return executeTransfer(request, null);
    }

    @Transactional
    public TransferResponse transferByEmail(
            String senderEmail, String recipientEmail, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> executeTransferByEmail(senderEmail, recipientEmail, amount, idempotencyKey));
        }
        return executeTransferByEmail(senderEmail, recipientEmail, amount, null);
    }

    private TransferResponse executeTransferByEmail(
            String senderEmail, String recipientEmail, BigDecimal amount, String idempotencyKey) {
        Wallet source = walletRepository.findByUserEmail(senderEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for email: " + senderEmail));
        Wallet target = walletRepository.findByUserEmail(recipientEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found for email: " + recipientEmail));

        if (source.getId().equals(target.getId())) {
            throw new SelfTransferException();
        }

        return executeTransfer(new TransferRequest(source.getId(), target.getId(), amount), idempotencyKey);
    }

    private TransferResponse executeTransfer(TransferRequest request, String idempotencyKey) {
        authorizationService.authorize();

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

        if (source.getUser().getUserType() == UserType.MERCHANT) {
            throw new MerchantTransferNotAllowedException(source.getId());
        }

        if (!source.getCurrency().equals(target.getCurrency())) {
            throw new BusinessException("Currency mismatch: cannot transfer between " +
                source.getCurrency() + " and " + target.getCurrency() + " wallets");
        }

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

        eventPublisher.publishEvent(new TransferCompletedEvent(
            saved.getId(), source.getId(), target.getId(),
            saved.getAmount(), saved.getCurrency()
        ));

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
