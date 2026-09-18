package com.hodi.modules.payments;

import com.hodi.common.AppConstant;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Every way money can move through Co-op, and what each implies.
 *
 * <h2>One bank</h2>
 *
 * <p>This list used to carry Daraja, Buni and Equity as well — the provider catalogue of a gateway this
 * platform was once assumed to sit behind. It does not: the marketplace reaches Co-op directly, Co-op is
 * the only institution with the financial ability to transact here, and a catalogue offering three banks
 * that cannot settle anything is a list somebody attaches an account to the wrong one from.
 *
 * <p>The central idea is worth stating once and keeping: <strong>the category decides the behaviour, never a
 * catalogue id.</strong> A screen that switches on primary keys breaks the moment a row is added and cannot
 * express a channel it has not met; a category can.
 *
 * <ul>
 *   <li>{@link Category#CASH} / {@link Category#CHEQUE} — recorded by staff, no gateway, no account.</li>
 *   <li>{@link Category#STK_PUSH} — the app asks Co-op to prompt a phone. Asynchronous: the call is
 *       acknowledged and the outcome arrives later, by notification or by asking for the status.</li>
 *   <li>{@link Category#TRANSFER} — outbound, staff only. Money going out, never a way to pay.</li>
 *   <li>{@link Category#VALIDATE} — inbound. Money arrives on its own and is matched by the account it
 *       landed in, which is what {@code CoopIpnService} does.</li>
 * </ul>
 */
public enum CoopChannel {

    /** A phone prompt. The customer approves on their handset and the money moves. */
    COOP_STK_PUSH(Category.STK_PUSH),

    /** Money out, to an account at Co-op or over PesaLink. Staff only, and behind Maker/Checker. */
    COOP_FUNDS_TRANSFER(Category.TRANSFER),
    COOP_PESALINK(Category.TRANSFER),

    /** Money in, on its own: Co-op tells us it arrived and we match it to what it was for. */
    COOP_BILLER(Category.VALIDATE),
    COOP_IPN_ACCOUNT(Category.VALIDATE);

    private final Category category;

    CoopChannel(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /**
     * The category of a stored provider string.
     *
     * <p>Falls back to a substring heuristic rather than refusing, on purpose: Co-op adds products before we
     * do, and a new {@code COOP_*_STK_PUSH} that classified as "unknown" would silently stop being offered
     * rather than working.
     */
    public static Category categoryOf(String provider) {
        if (provider == null || provider.isBlank()) return Category.CASH;
        return from(provider)
                .map(CoopChannel::category)
                .orElseGet(() -> {
                    String code = provider.toUpperCase(Locale.ROOT);
                    if (code.contains("STK_PUSH")) return Category.STK_PUSH;
                    if (code.contains("_FT")) return Category.TRANSFER;
                    if (code.contains("IPN") || code.contains("C2B") || code.contains("BILLER")) {
                        return Category.VALIDATE;
                    }
                    return Category.CASH;
                });
    }

    public static Optional<CoopChannel> from(String provider) {
        if (provider == null) return Optional.empty();
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(provider.trim()))
                .findFirst();
    }

    /**
     * The five kinds of money.
     *
     * <p>Stored on both payment tables as the string in {@code AppConstant.CHANNEL_*}, so a query can classify
     * without re-deriving and the CHECK constraints can be written in SQL.
     */
    public enum Category {
        CASH,
        CHEQUE,
        STK_PUSH,
        TRANSFER,
        VALIDATE;

        /** Recorded by hand, with no account behind it. */
        public boolean isManual() {
            return this == CASH || this == CHEQUE;
        }

        /** Whether a payment through it can be written down by staff. Only a transfer cannot: it is money out. */
        public boolean isReceivable() {
            return this != TRANSFER;
        }

        /**
         * How a pay screen should render this channel. One word, computed once here, so no screen re-encodes
         * the classification.
         */
        public String renderAs() {
            return switch (this) {
                case STK_PUSH -> "STK";
                case VALIDATE -> "VALIDATE";
                case TRANSFER -> "TRANSFER";
                case CHEQUE -> "CHEQUE";
                case CASH -> "CASH";
            };
        }

        /** The stored string, which is the same as the name and lives in {@link AppConstant} for SQL's sake. */
        public String code() {
            return name();
        }

        public static Category of(String code) {
            return valueOf(code);
        }
    }
}
