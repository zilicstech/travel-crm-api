package com.voyra.crm.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** The public apply form's CAPTCHA said no (400 CAPTCHA_FAILED) or could not be checked (503 CAPTCHA_UNAVAILABLE). */
@Getter
public class CaptchaException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CaptchaException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
