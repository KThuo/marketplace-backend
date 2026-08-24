package com.hodi.common.exception;

import org.springframework.http.HttpStatus;

public class DuplicateResourceException extends HodiException {
    public DuplicateResourceException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
