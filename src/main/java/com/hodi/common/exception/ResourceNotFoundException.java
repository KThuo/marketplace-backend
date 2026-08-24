package com.hodi.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends HodiException {
    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }

    public ResourceNotFoundException(String entity, Object id) {
        super("%s not found: %s".formatted(entity, id), HttpStatus.NOT_FOUND);
    }
}
