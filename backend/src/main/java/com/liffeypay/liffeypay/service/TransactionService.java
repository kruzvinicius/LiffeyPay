package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private static final Set<String> VALID_TYPES = Set.of("DEPOSIT", "WITHDRAWAL", "SENT", "RECEIVED");

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public Page<TransactionResponse> getTransactions(
            UUID walletId, String requestingEmail, String type, Pageable pageable) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        if (!wallet.getUser().getEmail().equals(requestingEmail)) {
            throw new ResourceNotFoundException("Wallet not found: " + walletId);
        }
        if (type != null && !VALID_TYPES.contains(type)) {
            throw new BusinessException(
                "Invalid transaction type filter: " + type +
                ". Valid values: DEPOSIT, WITHDRAWAL, SENT, RECEIVED");
        }
        Page<Transaction> page;
        if (type == null) {
            page = transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable);
        } else {
            page = switch (type) {
                case "DEPOSIT"    -> transactionRepository.findByTargetWalletIdAndType(walletId, TransactionType.DEPOSIT, pageable);
                case "WITHDRAWAL" -> transactionRepository.findBySourceWalletIdAndType(walletId, TransactionType.WITHDRAWAL, pageable);
                case "SENT"       -> transactionRepository.findBySourceWalletIdAndType(walletId, TransactionType.TRANSFER, pageable);
                case "RECEIVED"   -> transactionRepository.findByTargetWalletIdAndType(walletId, TransactionType.TRANSFER, pageable);
                default           -> throw new IllegalStateException("unreachable");
            };
        }
        return page.map(t -> toResponse(t, walletId));
    }

    private TransactionResponse toResponse(Transaction t, UUID walletId) {
        if (t.getType() == TransactionType.DEPOSIT) {
            return new TransactionResponse(t.getId(), "DEPOSIT", null,
                t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getCreatedAt());
        }
        if (t.getType() == TransactionType.WITHDRAWAL) {
            return new TransactionResponse(t.getId(), "WITHDRAWAL", null,
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
