package com.liffeypay.liffeypay.service;

import com.liffeypay.liffeypay.config.JwtProperties;
import com.liffeypay.liffeypay.dto.LoginRequest;
import com.liffeypay.liffeypay.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    public LoginResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        String token = jwtTokenService.generateToken((UserDetails) auth.getPrincipal());
        return new LoginResponse(token, jwtProperties.getExpirationMs() / 1000);
    }
}
