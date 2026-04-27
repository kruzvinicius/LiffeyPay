package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.DepositResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks DepositService depositService;

    private static final String OWNER_EMAIL = "owner@test.com";
    private static final UUID WALLET_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        User user = User.builder()
            .id(UUID.randomUUID()).fullName("Owner").email(OWNER_EMAIL)
            .documentNumber("11122233344").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build();
        wallet = Wallet.builder()
            .id(WALLET_ID).user(user)
            .balance(new BigDecimal("50.0000")).currency("EUR").build();
    }

    private Transaction savedDeposit(BigDecimal amount) {
        return Transaction.builder()
            .id(UUID.randomUUID()).targetWallet(wallet)
            .amount(amount).currency("EUR")
            .type(TransactionType.DEPOSIT).status(TransactionStatus.COMPLETED)
            .createdAt(Instant.now()).build();
    }

    @Test
    void deposit_happyPath_increasesBalanceAndSavesDeposit() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenReturn(savedDeposit(new BigDecimal("100.0000")));

        DepositResponse response = depositService.deposit(OWNER_EMAIL, new BigDecimal("100.00"), null);

        assertThat(wallet.getBalance()).isEqualByComparingTo("150.0000");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction tx = captor.getValue();
        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getSourceWallet()).isNull();
        assertThat(tx.getTargetWallet()).isEqualTo(wallet);

        assertThat(response.walletId()).isEqualTo(WALLET_ID);
    }

    @Test
    void deposit_idempotency_cachedResultReturnedWithoutExecuting() {
        String key = "deposit-key-abc";
        Transaction cached = savedDeposit(new BigDecimal("100.0000"));
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(cached));

        DepositResponse response = depositService.deposit(OWNER_EMAIL, new BigDecimal("100.00"), key);

        assertThat(response.id()).isEqualTo(cached.getId());
        verify(walletRepository, never()).findByUserEmailWithLock(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void deposit_newIdempotencyKey_executesAndSaves() {
        String key = "new-key-xyz";
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenReturn(savedDeposit(new BigDecimal("50.0000")));

        depositService.deposit(OWNER_EMAIL, new BigDecimal("50.00"), key);

        verify(transactionRepository).save(any());
    }

    @Test
    void deposit_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.deposit(OWNER_EMAIL, new BigDecimal("100.00"), null))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }
}
