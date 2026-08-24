package com.hodi.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base business exception. Carries an explicit HTTP status so the controller advice can
 * map directly without a switch on exception type.
 */
@Getter
public class HodiException extends RuntimeException {

    private final HttpStatus status;

    public HodiException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HodiException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
