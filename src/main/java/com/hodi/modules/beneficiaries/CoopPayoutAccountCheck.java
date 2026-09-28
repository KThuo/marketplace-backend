package com.hodi.modules.beneficiaries;

import com.hodi.modules.payments.CoopChannel;
import com.hodi.infra.coop.CoopTransferService;
import com.hodi.infra.coop.CoopTransferService.ResolvedAccount;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The same enquiry a disbursement makes before it is proposed, asked when a beneficiary is registered.
 *
 * <p>Through the account-validation channel in the catalogue, so switching that channel off stops this too —
 * and says so, rather than quietly leaving every new beneficiary unverified for a reason nobody can see.
 */
@Component
@RequiredArgsConstructor
public class CoopPayoutAccountCheck implements PayoutAccountCheck {

    /** Co-op's own code, when the form named no bank. */
    private static final String DEFAULT_BANK = "11";

    private final PaymentTypeRepository types;
    private final CoopTransferService coop;

    @Override
    public Answer check(String bankCode, String accountNo) {
        String code = bankCode == null || bankCode.isBlank() ? DEFAULT_BANK : bankCode.trim();
        String account = accountNo == null ? null : accountNo.trim();
        PaymentType enquiry = types.findByProviderType(CoopChannel.COOP_ACCOUNT_VALIDATION.name())
                .filter(PaymentType::isAvailable).orElse(null);
        if (enquiry == null) {
            return new Answer(account, code, null,
                    "Account validation is switched off, so the bank could not be asked who holds this account.");
        }
        ResolvedAccount resolved = coop.validate(enquiry, account, code);
        return new Answer(resolved.accountNumber(), resolved.bankCode(), resolved.holderName(), resolved.failure());
    }
}
