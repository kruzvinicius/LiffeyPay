package com.liffeypay.liffeypay.repository;

import com.liffeypay.liffeypay.domain.model.*;
import com.liffeypay.liffeypay.domain.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TransactionRepositoryTest extends RepositoryTestBase {

    @Autowired TestEntityManager em;
    @Autowired TransactionRepository repo;

    private Wallet walletA;
    private Wallet walletB;

    @BeforeEach
    void setUp() {
        User userA = em.persist(User.builder()
            .fullName("Alice").email("alice@repo.test")
            .documentNumber("11100011100").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
        User userB = em.persist(User.builder()
            .fullName("Bob").email("bob@repo.test")
            .documentNumber("22200022200").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
        walletA = em.persist(Wallet.builder().user(userA).currency("EUR").build());
        walletB = em.persist(Wallet.builder().user(userB).currency("EUR").build());
        em.flush();
    }

    @Test
    void findByIdempotencyKey_exists_returnsTransaction() {
        em.persist(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("10.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).idempotencyKey("key-001").build());
        em.flush();

        Optional<Transaction> result = repo.findByIdempotencyKey("key-001");

        assertThat(result).isPresent();
        assertThat(result.get().getIdempotencyKey()).isEqualTo("key-001");
    }

    @Test
    void findByIdempotencyKey_notFound_returnsEmpty() {
        Optional<Transaction> result = repo.findByIdempotencyKey("non-existent-key");

        assertThat(result).isEmpty();
    }

    @Test
    void findAllBySourceOrTarget_returnsBothDirections() {
        em.persist(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("20.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        em.persist(Transaction.builder()
            .sourceWallet(walletB).targetWallet(walletA)
            .amount(new BigDecimal("10.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        em.flush();

        Page<Transaction> result = repo.findAllBySourceWalletIdOrTargetWalletId(
            walletA.getId(), walletA.getId(), PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findAllBySourceOrTarget_pagination_respectsPageSize() {
        for (int i = 0; i < 3; i++) {
            em.persist(Transaction.builder()
                .sourceWallet(walletA).targetWallet(walletB)
                .amount(new BigDecimal("5.0000")).currency("EUR")
                .status(TransactionStatus.COMPLETED).build());
        }
        em.flush();

        Page<Transaction> result = repo.findAllBySourceWalletIdOrTargetWalletId(
            walletA.getId(), walletA.getId(), PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    void findAllBySourceOrTarget_ordering_latestFirst() throws InterruptedException {
        Transaction tx1 = em.persistAndFlush(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("1.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        Thread.sleep(50);
        Transaction tx3 = em.persistAndFlush(Transaction.builder()
            .sourceWallet(walletA).targetWallet(walletB)
            .amount(new BigDecimal("3.0000")).currency("EUR")
            .status(TransactionStatus.COMPLETED).build());
        em.clear();

        Page<Transaction> result = repo.findAllBySourceWalletIdOrTargetWalletId(
            walletA.getId(), walletA.getId(),
            PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(result.getContent().get(0).getId()).isEqualTo(tx3.getId());
        assertThat(result.getContent().get(1).getId()).isEqualTo(tx1.getId());
    }
}
