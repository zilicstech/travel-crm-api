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

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleFieldValidation(FieldValidationException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Validation failed - path: {}, field: {}", path, ex.getField());
        ApiErrorResponse response = ApiErrorResponse.of("Validation failed",
                "One or more request fields are invalid", null, HttpStatus.BAD_REQUEST.value(), path);
        response.setDetails(Map.of("errors", Map.of(ex.getField(), ex.getMessage())));
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

    /** A query/path parameter, header or multipart part that is missing or can't be read (a bad enum, a malformed date) is the caller's mistake. */
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> handleBadParameter(Exception ex, HttpServletRequest request) {
        String path = getPath(request);
        String message;
        if (ex instanceof org.springframework.web.bind.MissingServletRequestParameterException missing) {
            message = "The '" + missing.getParameterName() + "' parameter is required";
        } else if (ex instanceof org.springframework.web.bind.MissingRequestHeaderException missingHeader) {
            message = "The '" + missingHeader.getHeaderName() + "' header is required";
        } else if (ex instanceof org.springframework.web.multipart.support.MissingServletRequestPartException missingPart) {
            message = "The '" + missingPart.getRequestPartName() + "' part is required";
        } else {
            message = "The '" + ((org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) ex).getName()
                    + "' parameter has an invalid value";
        }
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

    /** A body type the endpoint does not take (e.g. JSON on a multipart upload): 415, decided before any handler runs. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(org.springframework.web.HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Unsupported content type - path: {}, type: {}", path, ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
                ApiErrorResponse.of("This endpoint does not accept that content type", "Unsupported media type",
                        null, HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), path));
    }

    /** A multipart body that cannot be parsed is the caller's mistake (the more specific 413 handler below wins for size). */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipart(org.springframework.web.multipart.MultipartException ex,
                                                            HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Unreadable multipart body - path: {}", path);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiErrorResponse.of("The upload could not be read - send it as multipart form data", "Invalid request body",
                        null, HttpStatus.BAD_REQUEST.value(), path));
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

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.of(ex.getMessage(), "Not found", null, HttpStatus.NOT_FOUND.value(), getPath(request)));
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
            log.warn("Duplicate value - path: {}, cause: {}", path, messageChain);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                    DuplicateConstraintMessageParser.humanMessage(messageChain), "Duplicate value", null,
                    HttpStatus.CONFLICT.value(), path));
        }

        log.warn("Data integrity violation - path: {}, cause: {}", path, cause);
        // The database's own text (SQL, column names) stays in the log, never in the response.
        String message = messageChain.contains("value too long")
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

    @ExceptionHandler(TenantWriteRefusedException.class)
    public ResponseEntity<ApiErrorResponse> handleTenantWriteRefused(TenantWriteRefusedException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Tenant write refused - code: {}, path: {}", ex.getCode(), path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Write refused", null, ex.getStatus().value(), path);
        response.setCode(ex.getCode());
        response.setSku(ex.getSku() == null ? null : ex.getSku().name());
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex,
                                                                      HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Insufficient wallet balance - available: {}, path: {}", ex.getAvailable(), path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Insufficient balance", null,
                HttpStatus.CONFLICT.value(), path);
        response.setCode("INSUFFICIENT_BALANCE");
        response.setDetails(Map.of("available", ex.getAvailable()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(PricingNotConfiguredException.class)
    public ResponseEntity<ApiErrorResponse> handlePricingNotConfigured(PricingNotConfiguredException ex,
                                                                       HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Pricing not configured - path: {}", path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Pricing not configured", null,
                HttpStatus.CONFLICT.value(), path);
        response.setCode("PRICING_NOT_CONFIGURED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(ReceivableDueException.class)
    public ResponseEntity<ApiErrorResponse> handleReceivableDue(ReceivableDueException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Receivable due - path: {}", path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Receivable due", null,
                HttpStatus.CONFLICT.value(), path);
        response.setCode("RECEIVABLE_DUE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(BookingRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBookingRequest(BookingRequestException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Booking request refused {} - path: {}", ex.getCode(), path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Invalid request", null, HttpStatus.BAD_REQUEST.value(), path);
        response.setCode(ex.getCode());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleBookingConflict(BookingConflictException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Booking conflict {} - path: {}", ex.getCode(), path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Booking conflict", null,
                HttpStatus.CONFLICT.value(), path);
        response.setCode(ex.getCode());
        response.setDetails(ex.getDetails());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(FareUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleFareUnavailable(FareUnavailableException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Fare unavailable - path: {}", path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Fare unavailable", null,
                HttpStatus.CONFLICT.value(), path);
        response.setCode("FARE_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
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

    @ExceptionHandler(com.voyra.crm.security.InquiryRateLimiter.TooManyInquiriesException.class)
    public ResponseEntity<ApiErrorResponse> handleTooManyInquiries(RuntimeException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("Rate limited - path: {}", path);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                ApiErrorResponse.of(ex.getMessage(), "Too many requests", null, HttpStatus.TOO_MANY_REQUESTS.value(), path));
    }

    @ExceptionHandler(CaptchaException.class)
    public ResponseEntity<ApiErrorResponse> handleCaptcha(CaptchaException ex, HttpServletRequest request) {
        String path = getPath(request);
        log.warn("CAPTCHA refused {} - path: {}", ex.getCode(), path);
        ApiErrorResponse response = ApiErrorResponse.of(ex.getMessage(), "Verification failed", null, ex.getStatus().value(), path);
        response.setCode(ex.getCode());
        return ResponseEntity.status(ex.getStatus()).body(response);
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

        // The detail stays in the log: an unexpected error's message can carry internals (a storage bucket and object
        // path, SQL, a stack frame) that must not reach the browser.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.of("Something went wrong on our side - please try again, or contact support if it keeps happening",
                        "Internal server error", null,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), path));
    }
}
