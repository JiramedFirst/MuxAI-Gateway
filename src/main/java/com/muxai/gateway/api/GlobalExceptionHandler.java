package com.muxai.gateway.api;

import com.muxai.gateway.api.dto.ErrorResponse;
import com.muxai.gateway.auth.ModelAccessDeniedException;
import com.muxai.gateway.cost.BudgetExceededException;
import com.muxai.gateway.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ModelAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleModelAccessDenied(ModelAccessDeniedException ex) {
        log.warn("Model access denied app_id={} model={}", ex.appId(), ex.requestedModel());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(ex.getMessage(), "permission_error", "MODEL_NOT_ALLOWED"));
    }

    @ExceptionHandler(BudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handleBudgetExceeded(BudgetExceededException ex) {
        log.warn("Budget exceeded app_id={} spent_usd={} cap_usd={}",
                ex.appId(), ex.spentUsd(), ex.capUsd());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(ex.getMessage(), "budget_exceeded", "BUDGET_EXHAUSTED"));
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> handleProvider(ProviderException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.code().clientStatus);
        String type = ex.code().retryable ? "upstream_error" : "gateway_error";
        log.warn("ProviderException provider={} code={} message={}",
                ex.providerId(), ex.code(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(ex.getMessage(), type, ex.code().name()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : e.toString())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(message, "invalid_request_error", "INVALID_REQUEST"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        "Malformed JSON request body",
                        "invalid_request_error",
                        "INVALID_REQUEST"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.getMessage(), "invalid_request_error", "INVALID_REQUEST"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        "Internal gateway error: " + ex.getMessage(),
                        "gateway_error",
                        "INTERNAL_ERROR"));
    }
}
