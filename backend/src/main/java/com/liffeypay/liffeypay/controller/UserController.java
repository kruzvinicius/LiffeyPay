package com.liffeypay.liffeypay.controller;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.dto.RegisterUserRequest;
import com.liffeypay.liffeypay.dto.UserResponse;
import com.liffeypay.liffeypay.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(@RequestBody @Valid RegisterUserRequest request) {
        return ApiResponse.ok(userService.register(request), "User registered successfully");
    }
}
