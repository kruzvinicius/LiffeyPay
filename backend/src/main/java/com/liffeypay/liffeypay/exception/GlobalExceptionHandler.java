package com.liffeypay.liffeypay.exception;

import com.liffeypay.liffeypay.dto.ApiResponse;
import com.liffeypay.liffeypay.exception.MerchantTransferNotAllowedException;
import com.liffeypay.liffeypay.exception.SelfTransferException;
import com.liffeypay.liffeypay.exception.TransferNotAuthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn("Transfer rejected - insufficient funds: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(DuplicateTransferException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDuplicateTransfer(DuplicateTransferException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(ResourceNotFoundException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(TransferNotAuthorizedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleTransferNotAuthorized(TransferNotAuthorizedException ex) {
        log.warn("Transfer not authorized: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(MerchantTransferNotAllowedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleMerchantTransfer(MerchantTransferNotAllowedException ex) {
        log.warn("Transfer rejected - merchant source: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(SelfTransferException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleSelfTransfer(SelfTransferException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusiness(BusinessException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                fe -> Objects.requireNonNullElse(fe.getDefaultMessage(), "Invalid value"),
                (existing, duplicate) -> existing
            ));
        return ApiResponse.validationError(errors);
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleBadCredentials(BadCredentialsException ex) {
        return ApiResponse.error("Invalid email or password");
    }

    @ExceptionHandler(JwtException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleJwtException(JwtException ex) {
        log.warn("JWT validation failed: {}", ex.getMessage());
        return ApiResponse.error("Invalid or expired token");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return ApiResponse.error("Conflict: possible duplicate idempotency key.");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ApiResponse.error("An unexpected error occurred. Please try again later.");
    }
}
