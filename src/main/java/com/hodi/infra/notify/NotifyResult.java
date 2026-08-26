package com.hodi.infra.notify;

/**
 * Outcome of a {@link NotifyClient} send.
 *
 * <p>{@link #correlationId} is the service's own message id when the response carries one — email answers
 * with {@code data.id}, and that is the id support can trace. SMS answers with {@code data: null}, and there
 * the id is generated locally: enough to tie a log line to a row when someone asks why a message did not
 * arrive, even though the service knows nothing about it.
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
