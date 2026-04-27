package com.liffeypay.liffeypay.repository;

import com.liffeypay.liffeypay.domain.model.User;
import com.liffeypay.liffeypay.domain.model.UserType;
import com.liffeypay.liffeypay.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserRepositoryTest extends RepositoryTestBase {

    @Autowired TestEntityManager em;
    @Autowired UserRepository repo;

    private User user;

    @BeforeEach
    void setUp() {
        user = em.persistAndFlush(User.builder()
            .fullName("Test User").email("user@user.test")
            .documentNumber("44400044400").passwordHash("hash")
            .userType(UserType.INDIVIDUAL).build());
    }

    @Test
    void findByEmail_found() {
        Optional<User> result = repo.findByEmail("user@user.test");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(user.getId());
    }

    @Test
    void findByEmail_notFound() {
        Optional<User> result = repo.findByEmail("nobody@user.test");

        assertThat(result).isEmpty();
    }

    @Test
    void existsByDocumentNumber_trueAndFalse() {
        assertThat(repo.existsByDocumentNumber("44400044400")).isTrue();
        assertThat(repo.existsByDocumentNumber("99999999999")).isFalse();
    }
}
