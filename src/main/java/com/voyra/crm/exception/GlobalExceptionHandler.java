package com.voyra.crm.exception;

import com.voyra.crm.dto.ApiErrorResponse;
import com.voyra.crm.security.SecurityContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private String getPath(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : "unknown";
    }

    private String getCause(Throwable ex) {
        Throwable cause = ex.getCause();
        return cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : null;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                    HttpServletRequest request) {
        String path = getPath(request);
        String cause = getCause(ex);

        log.warn("Bad request - message: {}, reason: IllegalArgumentException, cause: {}, path: {}",
                ex.getMessage(), cause, path);
        if (log.isDebugEnabled()) {
            log.debug("IllegalArgumentException stack trace", ex);
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(ex.getMessage(), "Invalid request", cause,
                        HttpStatus.BAD_REQUEST.value(), path));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                               HttpServletRequest request) {
        String path = getPath(request);
        Map<String, Object> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid",
                        (a, b) -> a + "; " + b));

        log.warn("Validation failed - path: {}, field errors: {}", path, fieldErrors);

        ApiErrorResponse response = ApiErrorResponse.of("Validation failed",
                "One or more request fields are invalid", null,
                HttpStatus.BAD_REQUEST.value(), path);
        response.setDetails(Map.of("errors", fieldErrors));
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex,
                                                                        HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Malformed request body - path: {}", path, ex);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of("The request body is malformed or contains an invalid value for one of its fields",
                        "Invalid request body", null, HttpStatus.BAD_REQUEST.value(), path));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                                     HttpServletRequest request) {
        String path = getPath(request);
        log.warn("No handler for path: {}", path);

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.of("The requested resource was not found", "Not found", null,
                        HttpStatus.NOT_FOUND.value(), path));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                          HttpServletRequest request) {
        String path = getPath(request);
        String messageChain = DuplicateConstraintMessageParser.collectMessageChain(ex);
        String cause = getCause(ex);

        if (DuplicateConstraintMessageParser.isDuplicateUniqueViolation(messageChain)) {
            String field = DuplicateConstraintMessageParser.extractFieldLabel(messageChain);
            log.warn("Duplicate value - field: {}, path: {}", field, path);
            ApiErrorResponse response = ApiErrorResponse.of(
                    "A record with this " + field + " already exists", "Duplicate value", cause,
                    HttpStatus.CONFLICT.value(), path);
            response.setDetails(Map.of("field", field));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        log.warn("Data integrity violation - path: {}, cause: {}", path, cause);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of("The request could not be completed due to a data conflict",
                        "Data integrity violation", cause, HttpStatus.BAD_REQUEST.value(), path));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex,
                                                                 HttpServletRequest request) {
        String path = getPath(request);
        String cause = getCause(ex);

        log.warn("Conflict - message: {}, reason: IllegalStateException, cause: {}, path: {}",
                ex.getMessage(), cause, path);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiErrorResponse.of(ex.getMessage(), "Invalid state for this operation", cause,
                        HttpStatus.CONFLICT.value(), path));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex,
                                                                     HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Concurrent modification - path: {}", path);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiErrorResponse.of("This record was modified by someone else - please refresh and retry",
                        "Concurrent modification", null, HttpStatus.CONFLICT.value(), path));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                                 HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Access denied - {} - path: {}", SecurityContextUtil.getAuditInfo(), path);

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiErrorResponse.of(ex.getMessage() != null ? ex.getMessage() : "Access denied",
                        "Forbidden", null, HttpStatus.FORBIDDEN.value(), path));
    }

    @ExceptionHandler(SupplierException.class)
    public ResponseEntity<ApiErrorResponse> handleSupplierException(SupplierException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.error("Supplier call failed - path: {}", path, ex);

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiErrorResponse.of("The supplier could not be reached. Please try again.",
                        "Supplier unavailable", null, HttpStatus.BAD_GATEWAY.value(), path));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        String path = getPath(request);
        String cause = getCause(ex);

        log.error("Unhandled exception - message: {}, reason: {}, cause: {}, path: {}",
                ex.getMessage(), ex.getClass().getSimpleName(), cause, path, ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.of(
                        ex.getMessage() != null ? ex.getMessage() : "Internal server error",
                        ex.getClass().getSimpleName(), cause,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), path));
    }
}
