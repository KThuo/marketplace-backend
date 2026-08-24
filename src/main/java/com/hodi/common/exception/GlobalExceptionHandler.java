package com.hodi.common.exception;

import com.hodi.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Catch-all controller advice. Every error path leaves the application through here, so the
 * {@link ApiResponse} envelope shape is identical for success and failure.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HodiException.class)
    public ResponseEntity<ApiResponse<Void>> handleHodiException(HodiException ex) {
        log.warn("HodiException: {} [status={}]", ex.getMessage(), ex.getStatus());
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String errors = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("Constraint violation: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedJson(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("Malformed request body"));
    }

    /**
     * The request body's content type is not one this endpoint accepts.
     *
     * <p>This is the exception a mislabelled upload actually raises — not MultipartException — and without a
     * handler it reached the catch-all and reported "An unexpected error occurred", which sent the reader to
     * the server logs for something the client had done. The message names the type that arrived, because
     * "application/json is not supported" on an upload endpoint is the whole diagnosis.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        String supplied = ex.getContentType() == null ? "none" : ex.getContentType().toString();
        log.warn("Unsupported request content type: {}", supplied);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiResponse.error(
                "This endpoint does not accept " + supplied + "."
                        + " For an upload, send multipart/form-data and let the browser set the header so the"
                        + " boundary is included."));
    }

    /**
     * A multipart request the container could not take apart.
     *
     * <p>Without this it fell through to the catch-all 500 and reported "An unexpected error occurred", which
     * is both untrue and unhelpful: the request was malformed, and the caller is the only one who can fix it.
     * The case that actually happens is a client that sets Content-Type itself and so loses the boundary —
     * which is exactly what axios does if an instance default is left in place — and the symptom was
     * indistinguishable from the server being broken.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException ex) {
        log.warn("Unreadable multipart request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(
                "That upload could not be read. Send it as multipart/form-data and let the browser set the "
                        + "Content-Type, so the multipart boundary is included."));
    }

    /** A required part or query parameter was absent — most often the file itself. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        log.warn("Missing request part: {}", ex.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("No " + ex.getRequestPartName() + " was included in that upload."));
    }

    /** Beyond the container's own multipart ceiling, before any of our own size checks can run. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeded the servlet limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("That file is too large to upload."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("Method " + ex.getMethod() + " not supported on this endpoint"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Endpoint not found"));
    }

    /**
     * The one that actually fires for an unmapped API path.
     *
     * <p>{@link NoHandlerFoundException} above is close to unreachable: a static resource handler is mapped at
     * {@code /**}, so an unmatched request reaches it and it raises this instead. Without a handler that lands
     * in the catch-all below and answers <em>500, "An unexpected error occurred"</em> — which is wrong on both
     * counts and actively misleading. A wrong URL then looks exactly like a server fault, and the real cause
     * is one line buried in a stack trace.
     *
     * <p>Logged at DEBUG rather than ERROR: a client asking for a path that does not exist is the client's
     * mistake, and at WARN or above every scanner probing for {@code /wp-login.php} would fill the log.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.debug("No handler for {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Endpoint not found"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        // Generic message — never reveal whether the username exists.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Invalid credentials"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Authentication required"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please try again later."));
    }
}
