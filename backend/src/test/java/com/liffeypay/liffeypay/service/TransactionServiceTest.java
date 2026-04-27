package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.model.TransactionType;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransactionResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks TransactionService transactionService;

    private final UUID walletId = UUID.fromString("aaaa0000-0000-0000-0000-000000000001");
    private final UUID otherId  = UUID.fromString("bbbb0000-0000-0000-0000-000000000002");
    private static final String OWNER_EMAIL = "owner@test.com";

    @Test
    void getTransactions_owner_returnsMixedPageWithCorrectTypes() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Wallet other  = Wallet.builder().id(otherId).balance(BigDecimal.ZERO).currency("EUR").build();

        Transaction sent = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet).targetWallet(other)
            .amount(new BigDecimal("50.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();
        Transaction received = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(other).targetWallet(wallet)
            .amount(new BigDecimal("30.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
            .thenReturn(new PageImpl<>(List.of(sent, received)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).type()).isEqualTo("SENT");
        assertThat(result.getContent().get(0).counterpartWalletId()).isEqualTo(otherId);
        assertThat(result.getContent().get(1).type()).isEqualTo("RECEIVED");
        assertThat(result.getContent().get(1).counterpartWalletId()).isEqualTo(otherId);
    }

    @Test
    void getTransactions_nonOwner_throwsResourceNotFoundException() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() ->
            transactionService.getTransactions(walletId, "hacker@evil.com", PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getTransactions_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findById(walletId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            transactionService.getTransactions(walletId, OWNER_EMAIL, PageRequest.of(0, 20)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getTransactions_deposit_returnsDepositTypeWithNullCounterpart() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Transaction deposit = Transaction.builder()
            .id(UUID.randomUUID()).targetWallet(wallet)
            .amount(new BigDecimal("100.0000")).currency("EUR")
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
            .thenReturn(new PageImpl<>(List.of(deposit)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, pageable);

        assertThat(result.getContent().get(0).type()).isEqualTo("DEPOSIT");
        assertThat(result.getContent().get(0).counterpartWalletId()).isNull();
    }

    @Test
    void getTransactions_withdrawal_returnsWithdrawalTypeWithNullCounterpart() {
        Wallet wallet = walletWithEmail(walletId, OWNER_EMAIL);
        Transaction withdrawal = Transaction.builder()
            .id(UUID.randomUUID()).sourceWallet(wallet)
            .amount(new BigDecimal("25.0000")).currency("EUR")
            .type(TransactionType.WITHDRAWAL)
            .status(TransactionStatus.COMPLETED).createdAt(Instant.now()).build();

        PageRequest pageable = PageRequest.of(0, 20);
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findAllBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable))
            .thenReturn(new PageImpl<>(List.of(withdrawal)));

        Page<TransactionResponse> result =
            transactionService.getTransactions(walletId, OWNER_EMAIL, pageable);

        assertThat(result.getContent().get(0).type()).isEqualTo("WITHDRAWAL");
        assertThat(result.getContent().get(0).counterpartWalletId()).isNull();
    }

    private Wallet walletWithEmail(UUID id, String email) {
        return Wallet.builder().id(id)
            .user(User.builder().id(UUID.randomUUID()).fullName("Owner").email(email)
                .documentNumber("12345678901").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .balance(BigDecimal.ZERO).currency("EUR").build();
    }
}
