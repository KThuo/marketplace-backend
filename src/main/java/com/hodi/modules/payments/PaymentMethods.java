package com.hodi.modules.payments;

import com.hodi.common.AppConstant;

import java.util.List;

/**
 * The coarse kind of money.
 *
 * <p><strong>Not a payment type.</strong> A payment type is a specific channel — a paybill, a bank account,
 * a phone prompt — configured per organisation. This is what the system did with the money, and is
 * deliberately the smaller idea, so a gateway-originated payment is the same row as one keyed in by staff.
 */
public final class PaymentMethods {

    private PaymentMethods() {}

    public static final List<String> ALL = List.of(
            AppConstant.PAY_CASH, AppConstant.PAY_CHEQUE, AppConstant.PAY_BANK_TRANSFER,
            AppConstant.PAY_MOBILE_MONEY, AppConstant.PAY_CARD, AppConstant.PAY_OTHER);

    /**
     * The two that can be written down by hand.
     *
     * <p>Cash and a cheque are somebody asserting money arrived; everything else arrives as a notification
     * from the bank and is placed from the statement it arrived on. A hand-keyed "bank transfer" is a
     * payment with nothing behind it, which is the hole the statement rule closes.
     */
    public static final List<String> MANUAL = List.of(AppConstant.PAY_CASH, AppConstant.PAY_CHEQUE);

    public static boolean isKnown(String method) {
        return method != null && ALL.contains(method);
    }

    public static boolean isManual(String method) {
        return method != null && MANUAL.contains(method);
    }

    public static String label(String method) {
        if (method == null) return "—";
        return switch (method) {
            case AppConstant.PAY_CASH -> "Cash";
            case AppConstant.PAY_CHEQUE -> "Cheque";
            case AppConstant.PAY_BANK_TRANSFER -> "Bank transfer";
            case AppConstant.PAY_MOBILE_MONEY -> "Mobile money";
            case AppConstant.PAY_CARD -> "Card";
            case AppConstant.PAY_OTHER -> "Other";
            default -> method;
        };
    }
}
