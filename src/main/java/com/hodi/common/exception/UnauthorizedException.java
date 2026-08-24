package com.hodi.common.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends HodiException {
    public UnauthorizedException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }
}
