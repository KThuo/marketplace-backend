package com.hodi.infra.notify;

/**
 * Outcome of a {@link NotifyClient} send.
 *
 * <p>The notify service returns a generic response without exposing a message id, so a locally-generated
 * {@link #correlationId} is what gets stored on the notification row — enough to tie a log line to a row when
 * someone asks why a message did not arrive.
 *
 * <p>{@code skipped} is distinct from {@code failed} on purpose. "SMS is switched off" and "the gateway
 * rejected it" both mean nothing was delivered, but only one of them is worth retrying or alerting on.
 */
public record NotifyResult(boolean success, boolean skipped, String correlationId, String error) {

    public static NotifyResult ok(String correlationId) {
        return new NotifyResult(true, false, correlationId, null);
    }

    public static NotifyResult skipped(String reason) {
        return new NotifyResult(false, true, null, reason);
    }

    public static NotifyResult failed(String error) {
        return new NotifyResult(false, false, null, error);
    }
}
