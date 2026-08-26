package com.hodi.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Uniform success/error envelope returned by every controller.
 *
 * <p>Callers wrap their payload via {@link #success(Object)} or surface an error via
 * {@link #error(String)}; {@link com.hodi.common.exception.GlobalExceptionHandler}
 * is the only producer of error envelopes outside service-thrown
 * {@link com.hodi.common.exception.HodiException}.
 *
 * <h3>Why validation errors carry a map as well as a message</h3>
 *
 * <p>A rejected body used to come back as one joined string — {@code "password: Password is required,
 * username: Username or email is required"} — which put the DTO's property names in front of a person
 * filling in a form and gave the screen no way to say which box was wrong. {@code errors} keys the
 * message by field so the form can put each one under its own input, and {@code message} is left as a
 * sentence somebody can read on its own.
 *
 * <p>The field is omitted from the JSON when absent, so every existing response is byte-identical.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    /**
     * Field name to message, for a body that failed validation. Null — and absent from the JSON —
     * for every other kind of response, including business errors, which are about the request as a
     * whole rather than one input.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> errors;

    @Builder.Default
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp = LocalDateTime.now();

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message("Request processed successfully")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .data(null)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * A body that failed validation: the per-field messages, plus a summary for the form's own banner.
     *
     * @param message  a sentence with no field names in it
     * @param errors   field name to message, in the order the fields were declared
     */
    public static <T> ApiResponse<T> validationError(String message, Map<String, String> errors) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .data(null)
                .errors(errors)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
