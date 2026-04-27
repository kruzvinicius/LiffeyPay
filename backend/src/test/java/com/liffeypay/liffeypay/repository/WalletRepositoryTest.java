package com.liffeypay.liffeypay.repository;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class WalletRepositoryTest extends RepositoryTestBase {

    @Autowired TestEntityManager em;
    @Autowired WalletRepository repo;

    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        user = em.persist(User.builder()
            .fullName("Owner").email("owner@wallet.test")
            .documentNumber("33300033300").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
        wallet = em.persist(Wallet.builder().user(user).currency("EUR").build());
        em.flush();
    }

    @Test
    void findByUser_Id_found() {
        Optional<Wallet> result = repo.findByUser_Id(user.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wallet.getId());
    }

    @Test
    void findByUser_Id_notFound() {
        Optional<Wallet> result = repo.findByUser_Id(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void findByUserEmail_found() {
        Optional<Wallet> result = repo.findByUserEmail("owner@wallet.test");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wallet.getId());
        assertThat(result.get().getUser().getEmail()).isEqualTo("owner@wallet.test");
    }

    @Test
    void findByIdWithLock_found() {
        Optional<Wallet> result = repo.findByIdWithLock(wallet.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(wallet.getId());
        assertThat(result.get().getCurrency()).isEqualTo("EUR");
    }
}
