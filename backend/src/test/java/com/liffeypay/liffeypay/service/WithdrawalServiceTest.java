package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.WithdrawalResponse;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
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
class WithdrawalServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AuthorizationService authorizationService;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @InjectMocks WithdrawalService withdrawalService;

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
            .balance(new BigDecimal("100.0000")).currency("EUR").build();
    }

    private Transaction savedWithdrawal(BigDecimal amount) {
        return Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet)
            .amount(amount).currency("EUR")
            .type(TransactionType.WITHDRAWAL).status(TransactionStatus.COMPLETED)
            .createdAt(Instant.now()).build();
    }

    @Test
    void withdraw_happyPath_decreasesBalanceAndSavesWithdrawal() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenReturn(savedWithdrawal(new BigDecimal("30.0000")));

        WithdrawalResponse response = withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), null);

        assertThat(wallet.getBalance()).isEqualByComparingTo("70.0000");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction tx = captor.getValue();
        assertThat(tx.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(tx.getSourceWallet()).isEqualTo(wallet);
        assertThat(tx.getTargetWallet()).isNull();

        assertThat(response.walletId()).isEqualTo(WALLET_ID);
    }

    @Test
    void withdraw_idempotency_cachedResultReturnedWithoutExecuting() {
        String key = "withdraw-key-abc";
        Transaction cached = savedWithdrawal(new BigDecimal("30.0000"));
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(cached));

        WithdrawalResponse response = withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), key);

        assertThat(response.id()).isEqualTo(cached.getId());
        verify(walletRepository, never()).findByUserEmailWithLock(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_insufficientFunds_throwsInsufficientFundsException() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("200.00"), null))
            .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_authorizationDenied_throwsTransferNotAuthorizedException() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        doThrow(new TransferNotAuthorizedException("Denied"))
            .when(authorizationService).authorize();

        assertThatThrownBy(() -> withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), null))
            .isInstanceOf(TransferNotAuthorizedException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), null))
            .isInstanceOf(com.liffeypay.liffeypay.exception.ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void withdraw_newIdempotencyKey_executesAndSaves() {
        String key = "new-key-xyz";
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenReturn(savedWithdrawal(new BigDecimal("30.0000")));

        withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), key);

        verify(transactionRepository).save(any());
    }

    @Test
    void withdraw_success_publishesWithdrawalEvent() {
        Transaction saved = savedWithdrawal(new BigDecimal("30.0000"));
        when(walletRepository.findByUserEmailWithLock(OWNER_EMAIL)).thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenReturn(saved);

        withdrawalService.withdraw(OWNER_EMAIL, new BigDecimal("30.00"), null);

        ArgumentCaptor<TransferCompletedEvent> captor = ArgumentCaptor.forClass(TransferCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TransferCompletedEvent event = captor.getValue();
        assertThat(event.transactionId()).isEqualTo(saved.getId());
        assertThat(event.sourceWalletId()).isEqualTo(WALLET_ID);
        assertThat(event.targetWalletId()).isNull();
        assertThat(event.amount()).isEqualByComparingTo("30.0000");
        assertThat(event.currency()).isEqualTo("EUR");
    }
}
