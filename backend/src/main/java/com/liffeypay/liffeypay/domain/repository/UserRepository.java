package com.liffeypay.liffeypay.domain.repository;

import com.liffeypay.liffeypay.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByDocumentNumber(String documentNumber);
}
