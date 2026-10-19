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
import org.springframework.web.HttpRequestMethodNotSupportedException;
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

    /** A query/path parameter that is missing or can't be read (a bad enum, a malformed date) is the caller's mistake. */
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleBadParameter(Exception ex, HttpServletRequest request) {
        String path = getPath(request);
        String message = ex instanceof org.springframework.web.bind.MissingServletRequestParameterException missing
                ? "The '" + missing.getParameterName() + "' parameter is required"
                : "The '" + ((org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) ex).getName()
                        + "' parameter has an invalid value";
        log.warn("Bad request parameter - path: {}, {}", path, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(message, "Invalid request parameter", null, HttpStatus.BAD_REQUEST.value(), path));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                        HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Unsupported HTTP method - path: {}, method: {}", path, ex.getMethod());

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                ApiErrorResponse.of("This HTTP method is not supported for this endpoint", "Method not allowed",
                        null, HttpStatus.METHOD_NOT_ALLOWED.value(), path));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Upload rejected as too large - path: {}", path);

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiErrorResponse.of("That file is too large. The maximum size is 10 MB.", "File too large", null,
                        HttpStatus.PAYLOAD_TOO_LARGE.value(), path));
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

    @ExceptionHandler(StoredFileNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleStoredFileNotFound(StoredFileNotFoundException ex,
                                                                     HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.of(ex.getMessage(), "Not found", null, HttpStatus.NOT_FOUND.value(), getPath(request)));
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
                    "A record with this " + field + " already exists", "Duplicate value", null,
                    HttpStatus.CONFLICT.value(), path);
            response.setDetails(Map.of("field", field));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        log.warn("Data integrity violation - path: {}, cause: {}", path, cause);
        // The database's own text (SQL, column names) stays in the log, never in the response.
        String message = messageChain != null && messageChain.contains("value too long")
                ? tooLongMessage(messageChain)
                : "The request could not be completed due to a data conflict";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of(message, "Data integrity violation", null, HttpStatus.BAD_REQUEST.value(), path));
    }

    /** Postgres names the limit ("character varying(150)") but not the column, so the message states the limit. */
    private static String tooLongMessage(String messageChain) {
        java.util.regex.Matcher limit = java.util.regex.Pattern.compile("varying\\((\\d+)\\)").matcher(messageChain);
        return limit.find()
                ? "A value is longer than the " + limit.group(1) + " characters allowed - shorten it and try again"
                : "One of the values is too long - shorten it and try again";
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
