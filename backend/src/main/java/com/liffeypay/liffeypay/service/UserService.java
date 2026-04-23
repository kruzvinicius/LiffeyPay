package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.domain.model.User;
import com.liffeypay.liffeypay.domain.model.Wallet;
import com.liffeypay.liffeypay.domain.repository.UserRepository;
import com.liffeypay.liffeypay.domain.repository.WalletRepository;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import com.liffeypay.liffeypay.dto.UserResponse;
import com.liffeypay.liffeypay.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse register(RegisterUserRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new BusinessException("Email already registered: " + request.email());
        }
        if (userRepository.existsByDocumentNumber(request.documentNumber())) {
            throw new BusinessException("Document number already registered");
        }

        User user = userRepository.save(User.builder()
            .fullName(request.fullName())
            .email(request.email())
            .documentNumber(request.documentNumber())
            .passwordHash(passwordEncoder.encode(request.password()))
            .userType(request.userType())
            .build());

        String currency = (request.currency() != null && !request.currency().isBlank())
            ? request.currency().toUpperCase()
            : "EUR";

        Wallet wallet = walletRepository.save(Wallet.builder()
            .user(user)
            .balance(BigDecimal.ZERO)
            .currency(currency)
            .build());

        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), wallet.getId());
    }
}
