package com.hodi.modules.beneficiaries;

/**
 * Who holds a payout account, asked of the bank.
 *
 * <p>An interface so a test can answer without the bank being reachable from wherever it runs.
 * {@link CoopPayoutAccountCheck} is the one production implementation.
 */
public interface PayoutAccountCheck {

    /**
     * @param holderName the name the bank holds the account in, when it confirmed one
     * @param failure    why it did not, in the bank's words or ours; null when it did
     * @param bankCode   the code as the bank wants it quoted, when confirmed
     */
    record Answer(String accountNo, String bankCode, String holderName, String failure) {
        public boolean confirmed() { return failure == null && holderName != null; }
    }

    Answer check(String bankCode, String accountNo);
}
