package com.hodi.common.exception;

import org.springframework.http.HttpStatus;

public class PasswordPolicyException extends HodiException {
    public PasswordPolicyException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
