package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.Transaction;
import com.liffeypay.liffeypay.domain.model.TransactionStatus;
import com.liffeypay.liffeypay.domain.model.User;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.TransferRequest;
import com.liffeypay.liffeypay.dto.TransferResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import com.liffeypay.liffeypay.exception.InsufficientFundsException;
import org.springframework.context.ApplicationEventPublisher;
import com.liffeypay.liffeypay.exception.MerchantTransferNotAllowedException;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import com.liffeypay.liffeypay.exception.SelfTransferException;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class TransferServiceTest {

    @Mock WalletRepository walletRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AuthorizationService authorizationService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks TransferService transferService;

    // sourceId < targetId ensures sourceFirst = true (consistent lock order in tests)
    private final UUID sourceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private Wallet source;
    private Wallet target;

    @BeforeEach
    void setUp() {
        source = Wallet.builder()
            .id(sourceId).balance(new BigDecimal("100.0000")).currency("EUR")
            .user(User.builder()
                .id(UUID.randomUUID()).fullName("Sender").email("sender@test.com")
                .documentNumber("12345678901").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .build();
        target = Wallet.builder()
            .id(targetId).balance(new BigDecimal("50.0000")).currency("EUR")
            .user(User.builder()
                .id(UUID.randomUUID()).fullName("Receiver").email("receiver@test.com")
                .documentNumber("98765432100").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .build();
    }

    @Test
    void transfer_happyPath_debitSourceCreditTarget() {
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
        when(transactionRepository.save(any())).thenReturn(savedTx(sourceId, targetId, new BigDecimal("30.0000")));

        TransferResponse response = transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("30.0000")), null);

        assertThat(source.getBalance()).isEqualByComparingTo("70.0000");
        assertThat(target.getBalance()).isEqualByComparingTo("80.0000");
        assertThat(response.amount()).isEqualByComparingTo("30.0000");
        assertThat(response.status()).isEqualTo("COMPLETED");
    }

    @Test
    void transfer_insufficientFunds_throwsInsufficientFundsException() {
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("200.0000")), null))
            .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_sourceWalletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void transfer_targetWalletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void transfer_merchantSource_throwsMerchantTransferNotAllowedException() {
        Wallet merchantWallet = Wallet.builder()
            .id(sourceId).balance(new BigDecimal("100.0000")).currency("EUR")
            .user(User.builder()
                .id(UUID.randomUUID()).fullName("Shop").email("shop@test.com")
                .documentNumber("12345678000100").passwordHash("hash")
                .userType(UserType.MERCHANT).build())
            .build();

        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(merchantWallet));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
            .isInstanceOf(MerchantTransferNotAllowedException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_success_publishesTransferCompletedEvent() {
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
        when(transactionRepository.save(any()))
            .thenReturn(savedTx(sourceId, targetId, new BigDecimal("10.0000")));

        transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null);

        verify(eventPublisher).publishEvent(any(TransferCompletedEvent.class));
    }

    @Test
    void transfer_currencyMismatch_throwsBusinessException() {
        Wallet gbpTarget = Wallet.builder()
            .id(targetId).balance(new BigDecimal("50.0000")).currency("GBP")
            .user(User.builder()
                .id(UUID.randomUUID()).fullName("Receiver").email("receiver@test.com")
                .documentNumber("98765432100").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .build();

        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(gbpTarget));

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Currency mismatch");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_deniedByAuthorizer_throwsTransferNotAuthorizedException() {
        doThrow(new TransferNotAuthorizedException("Transfer denied"))
            .when(authorizationService).authorize(any());

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
            .isInstanceOf(TransferNotAuthorizedException.class);

        verify(walletRepository, never()).findByIdWithLock(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_authorizerUnavailable_throwsTransferNotAuthorizedException() {
        doThrow(new TransferNotAuthorizedException("Authorization service unavailable"))
            .when(authorizationService).authorize(any());

        assertThatThrownBy(() -> transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), null))
            .isInstanceOf(TransferNotAuthorizedException.class);
    }

    @Test
    void transfer_withExistingIdempotencyKey_returnsCachedResultWithoutExecuting() {
        String key = "idem-key-123";
        when(transactionRepository.findByIdempotencyKey(key))
            .thenReturn(Optional.of(savedTx(sourceId, targetId, new BigDecimal("30.0000"))));

        TransferResponse response = transferService.transfer(
            new TransferRequest(sourceId, targetId, new BigDecimal("30.0000")), key);

        assertThat(response.amount()).isEqualByComparingTo("30.0000");
        verify(walletRepository, never()).findByIdWithLock(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transfer_withNewIdempotencyKey_executesAndSavesTransaction() {
        String key = "new-key-456";
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
        when(transactionRepository.save(any())).thenReturn(savedTx(sourceId, targetId, new BigDecimal("10.0000")));

        transferService.transfer(new TransferRequest(sourceId, targetId, new BigDecimal("10.0000")), key);

        verify(transactionRepository).save(any());
    }

    @Test
    void transfer_lockOrderIsConsistent_whenSourceComparesLowerBySignedLong() {
        // UUID.compareTo() uses signed long comparison.
        // 0xffffffff... as a signed long is -1, which is LESS than 0x000...0001 (= 1).
        // So bigSource.compareTo(smallTarget) < 0 → sourceFirst = true → bigSource locked first.
        UUID bigSource = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID smallTarget = UUID.fromString("00000000-0000-0000-0000-000000000001");

        Wallet bigSourceWallet = Wallet.builder()
            .id(bigSource).balance(new BigDecimal("100.0000")).currency("EUR")
            .user(User.builder()
                .id(UUID.randomUUID()).fullName("Big").email("big@test.com")
                .documentNumber("11111111111").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .build();
        Wallet smallTargetWallet = Wallet.builder()
            .id(smallTarget).balance(BigDecimal.ZERO).currency("EUR")
            .user(User.builder()
                .id(UUID.randomUUID()).fullName("Small").email("small@test.com")
                .documentNumber("22222222222").passwordHash("hash")
                .userType(UserType.INDIVIDUAL).build())
            .build();

        when(walletRepository.findByIdWithLock(bigSource)).thenReturn(Optional.of(bigSourceWallet));
        when(walletRepository.findByIdWithLock(smallTarget)).thenReturn(Optional.of(smallTargetWallet));
        when(transactionRepository.save(any())).thenReturn(savedTx(bigSource, smallTarget, new BigDecimal("10.0000")));

        transferService.transfer(new TransferRequest(bigSource, smallTarget, new BigDecimal("10.0000")), null);

        // bigSource (signed -1) is < smallTarget (signed +1), so bigSource locked first
        var inOrder = inOrder(walletRepository);
        inOrder.verify(walletRepository).findByIdWithLock(bigSource);
        inOrder.verify(walletRepository).findByIdWithLock(smallTarget);
    }

    @Test
    void transferByEmail_happyPath_debitSourceCreditTarget() {
        when(walletRepository.findByUserEmail("sender@test.com")).thenReturn(Optional.of(source));
        when(walletRepository.findByUserEmail("receiver@test.com")).thenReturn(Optional.of(target));
        when(walletRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(walletRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
        when(transactionRepository.save(any())).thenReturn(savedTx(sourceId, targetId, new BigDecimal("40.0000")));

        TransferResponse response = transferService.transferByEmail(
            "sender@test.com", "receiver@test.com", new BigDecimal("40.00"), null);

        assertThat(source.getBalance()).isEqualByComparingTo("60.0000");
        assertThat(target.getBalance()).isEqualByComparingTo("90.0000");
        assertThat(response.sourceWalletId()).isEqualTo(sourceId);
        assertThat(response.targetWalletId()).isEqualTo(targetId);
    }

    @Test
    void transferByEmail_selfTransfer_throwsSelfTransferException() {
        when(walletRepository.findByUserEmail("sender@test.com")).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> transferService.transferByEmail(
            "sender@test.com", "sender@test.com", new BigDecimal("10.00"), null))
            .isInstanceOf(SelfTransferException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferByEmail_recipientNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByUserEmail("sender@test.com")).thenReturn(Optional.of(source));
        when(walletRepository.findByUserEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transferByEmail(
            "sender@test.com", "ghost@test.com", new BigDecimal("10.00"), null))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferByEmail_senderNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findByUserEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transferByEmail(
            "nobody@test.com", "receiver@test.com", new BigDecimal("10.00"), null))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    private Transaction savedTx(UUID srcId, UUID tgtId, BigDecimal amount) {
        Wallet src = Wallet.builder().id(srcId).currency("EUR").build();
        Wallet tgt = Wallet.builder().id(tgtId).currency("EUR").build();
        return Transaction.builder()
            .id(UUID.randomUUID())
            .sourceWallet(src)
            .targetWallet(tgt)
            .amount(amount)
            .currency("EUR")
            .status(TransactionStatus.COMPLETED)
            .createdAt(Instant.now())
            .build();
    }
}
