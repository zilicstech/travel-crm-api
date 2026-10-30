package com.voyra.crm.exception;

import com.voyra.crm.dto.ApiErrorResponse;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.security.InquiryRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Handler methods called directly, for the mappings the fixture-controller test does not drive. */
class GlobalExceptionHandlerDirectTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/thing");

    private static ApiErrorResponse body(ResponseEntity<ApiErrorResponse> r, HttpStatus status) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getStatus()).isEqualTo(status.value());
        return r.getBody();
    }

    @Test
    void withoutARequestThePathReadsUnknownAndTheCauseIsNamed() {
        ApiErrorResponse b = body(handler.handleIllegalArgument(new IllegalArgumentException("bad", new RuntimeException("root")), null),
                HttpStatus.BAD_REQUEST);
        assertThat(b.getPath()).isEqualTo("unknown");
        assertThat(b.getCause()).isEqualTo("RuntimeException: root");
    }

    @Test
    void aFieldValidationErrorNamesTheField() {
        ApiErrorResponse b = body(handler.handleFieldValidation(new FieldValidationException("email", "is taken"), request), HttpStatus.BAD_REQUEST);
        assertThat(b.getMessage()).isEqualTo("Validation failed");
        assertThat(b.getDetails()).isEqualTo(Map.of("errors", Map.of("email", "is taken")));
        assertThat(b.getPath()).isEqualTo("/api/thing");
    }

    @Test
    void anUnreadableBodyIs400WithAPlainMessage() {
        ApiErrorResponse b = body(handler.handleMessageNotReadable(
                new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0])), request), HttpStatus.BAD_REQUEST);
        assertThat(b.getMessage()).isEqualTo("The request body is malformed or contains an invalid value for one of its fields");
    }

    @Test
    void aMissingHeaderOrPartIsNamed() throws Exception {
        MethodParameter param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
        assertThat(body(handler.handleBadParameter(new MissingRequestHeaderException("X-Key", param), request), HttpStatus.BAD_REQUEST).getMessage())
                .isEqualTo("The 'X-Key' header is required");
        assertThat(body(handler.handleBadParameter(new MissingServletRequestPartException("file"), request), HttpStatus.BAD_REQUEST).getMessage())
                .isEqualTo("The 'file' part is required");
    }

    @Test
    void anUnparseableUploadIs400() {
        assertThat(body(handler.handleMultipart(new MultipartException("boom"), request), HttpStatus.BAD_REQUEST).getMessage())
                .isEqualTo("The upload could not be read - send it as multipart form data");
    }

    @Test
    void aMissingStoredFileIs404() {
        assertThat(body(handler.handleStoredFileNotFound(new StoredFileNotFoundException(), request), HttpStatus.NOT_FOUND).getMessage())
                .isEqualTo("File not found");
    }

    @Test
    void aDuplicateKeyIs409WithTheIndexMessageNeverTheSql() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"idx_agent_email\""));
        ApiErrorResponse b = body(handler.handleDataIntegrityViolation(ex, request), HttpStatus.CONFLICT);
        assertThat(b.getMessage()).isEqualTo("This email is already used by another login");
        assertThat(b.getCause()).isNull();
    }

    @Test
    void anOtherIntegrityViolationIs400WithAGenericMessage() {
        ApiErrorResponse b = body(handler.handleDataIntegrityViolation(new DataIntegrityViolationException("fk violated"), request), HttpStatus.BAD_REQUEST);
        assertThat(b.getMessage()).isEqualTo("The request could not be completed due to a data conflict");
    }

    @Test
    void aTooLongValueWithoutALimitSaysSoPlainly() {
        ApiErrorResponse b = body(handler.handleDataIntegrityViolation(new DataIntegrityViolationException("value too long for type text"), request),
                HttpStatus.BAD_REQUEST);
        assertThat(b.getMessage()).isEqualTo("One of the values is too long - shorten it and try again");
    }

    @Test
    void aTenantRefusalCarriesItsStatusCodeAndSku() {
        ApiErrorResponse sku = body(handler.handleTenantWriteRefused(TenantWriteRefusedException.skuRequired(Sku.VISA), request), HttpStatus.PAYMENT_REQUIRED);
        assertThat(sku.getCode()).isEqualTo("SKU_REQUIRED");
        assertThat(sku.getSku()).isEqualTo("VISA");
        ApiErrorResponse suspended = body(handler.handleTenantWriteRefused(TenantWriteRefusedException.suspended(), request), HttpStatus.LOCKED);
        assertThat(suspended.getCode()).isEqualTo("TENANT_SUSPENDED");
        assertThat(suspended.getSku()).isNull();
    }

    @Test
    void unconfiguredPricingIs409WithItsCode() {
        ApiErrorResponse b = body(handler.handlePricingNotConfigured(new PricingNotConfiguredException(), request), HttpStatus.CONFLICT);
        assertThat(b.getCode()).isEqualTo("PRICING_NOT_CONFIGURED");
    }

    @Test
    void aConcurrentEditIs409() {
        ApiErrorResponse b = body(handler.handleOptimisticLocking(new ObjectOptimisticLockingFailureException(Object.class, "id"), request), HttpStatus.CONFLICT);
        assertThat(b.getMessage()).isEqualTo("This record was modified by someone else - please refresh and retry");
    }

    @Test
    void accessDeniedWithoutAMessageSaysAccessDenied() {
        assertThat(body(handler.handleAccessDenied(new AccessDeniedException(null), request), HttpStatus.FORBIDDEN).getMessage()).isEqualTo("Access denied");
        assertThat(body(handler.handleAccessDenied(new AccessDeniedException("Owner only"), request), HttpStatus.FORBIDDEN).getMessage()).isEqualTo("Owner only");
    }

    @Test
    void tooManyInquiriesIs429() {
        assertThat(body(handler.handleTooManyInquiries(new InquiryRateLimiter.TooManyInquiriesException(), request), HttpStatus.TOO_MANY_REQUESTS).getReason())
                .isEqualTo("Too many requests");
    }

    @Test
    void aCaptchaRefusalKeepsItsStatusAndCode() {
        ApiErrorResponse b = body(handler.handleCaptcha(new CaptchaException(HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_UNAVAILABLE", "try later"), request),
                HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(b.getCode()).isEqualTo("CAPTCHA_UNAVAILABLE");
    }

    @Test
    void anUnexpectedErrorNeverLeaksItsMessage() {
        ApiErrorResponse b = body(handler.handleGeneric(new Exception("gs://bucket/secret/path", new RuntimeException("sql")), request),
                HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(b.getMessage()).doesNotContain("bucket").startsWith("Something went wrong on our side");
        assertThat(b.getCause()).isNull();
    }

    @Test
    void withDebugLoggingTheStackTraceOfABadArgumentIsLogged() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.classic.Level before = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (com.voyra.crm.support.LogCapture log = new com.voyra.crm.support.LogCapture(GlobalExceptionHandler.class)) {
            body(handler.handleIllegalArgument(new IllegalArgumentException("bad"), request), HttpStatus.BAD_REQUEST);
            assertThat(log.lines()).contains("DEBUG IllegalArgumentException stack trace");
        } finally {
            logger.setLevel(before);
        }
    }

    @Test
    void aFieldErrorWithoutAMessageReadsInvalidAndRepeatedFieldsAreJoined() throws Exception {
        org.springframework.validation.BeanPropertyBindingResult result = new org.springframework.validation.BeanPropertyBindingResult(new Object(), "req");
        result.addError(new org.springframework.validation.FieldError("req", "name", null, false, null, null, null));
        result.addError(new org.springframework.validation.FieldError("req", "email", "must be valid"));
        result.addError(new org.springframework.validation.FieldError("req", "email", "is taken"));
        MethodParameter param = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);

        ApiErrorResponse b = body(handler.handleValidation(new org.springframework.web.bind.MethodArgumentNotValidException(param, result), request),
                HttpStatus.BAD_REQUEST);

        assertThat(b.getDetails()).isEqualTo(Map.of("errors", Map.of("name", "Invalid", "email", "must be valid; is taken")));
    }
}
