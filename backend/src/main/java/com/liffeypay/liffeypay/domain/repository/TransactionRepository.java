package com.liffeypay.liffeypay.domain.repository;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Page<Transaction> findAllBySourceWalletIdOrTargetWalletId(UUID sourceWalletId, UUID targetWalletId, Pageable pageable);
    Page<Transaction> findByTargetWalletIdAndType(UUID targetWalletId, TransactionType type, Pageable pageable);
    Page<Transaction> findBySourceWalletIdAndType(UUID sourceWalletId, TransactionType type, Pageable pageable);
}
