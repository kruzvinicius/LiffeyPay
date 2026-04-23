package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.User;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.WalletResponse;
import com.liffeypay.liffeypay.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock WalletRepository walletRepository;
    @InjectMocks WalletService walletService;

    private static final UUID WALLET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID   = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String OWNER_EMAIL = "owner@test.com";

    private Wallet ownerWallet;

    @BeforeEach
    void setUp() {
        User owner = User.builder().id(USER_ID).email(OWNER_EMAIL).build();
        ownerWallet = Wallet.builder()
                .id(WALLET_ID)
                .user(owner)
                .balance(new BigDecimal("100.0000"))
                .currency("EUR")
                .build();
    }

    @Test
    void getById_ownerAccess_returnsWalletResponse() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(ownerWallet));

        WalletResponse response = walletService.getById(WALLET_ID, OWNER_EMAIL);

        assertThat(response.walletId()).isEqualTo(WALLET_ID);
        assertThat(response.balance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void getById_nonOwnerAccess_throwsResourceNotFoundException() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(ownerWallet));

        assertThatThrownBy(() -> walletService.getById(WALLET_ID, "hacker@evil.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_walletNotFound_throwsResourceNotFoundException() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getById(WALLET_ID, OWNER_EMAIL))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
