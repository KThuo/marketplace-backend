package com.hodi.modules.payments;

/**
 * An account became one money may be matched to: approved, or switched back on.
 *
 * <p>An event rather than a call, because the thing that cares — the notification service, which holds
 * credits that arrived before the account existed — sits in the bank integration, and the account service
 * must not depend on it. The listener runs in the same transaction, so the retry sees the account.
 */
public record PaymentAccountWentLive(Long accountId) {}
