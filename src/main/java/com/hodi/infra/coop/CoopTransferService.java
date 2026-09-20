package com.hodi.infra.coop;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Money going out: validating where it is going, sending it, and asking what became of it.
 *
 * <h2>Validation is not optional</h2>
 *
 * <p>An account number is thirteen digits with no redundancy, and a transposed pair is another
 * well-formed account belonging to somebody else. So the destination is resolved first and the holder's
 * name comes back — that name is what a person approves, because "KES 450,000 to 01102901454001" is not
 * something anybody can check, and "KES 450,000 to JANE W. MWANGI" is.
 *
 * <p>Money that reaches the wrong account is not recoverable by this platform, by the sending
 * organisation, or in practice at all. It is the one mistake here with no undo, which is why the
 * validation call is a precondition rather than a convenience.
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not decide to send anything. Approval, the Maker/Checker gate and the intent that records
 * the attempt belong above it; this speaks the bank's protocol and nothing else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoopTransferService {

    private final CoopClient coop;
    private final ConfigurationService configs;

    /** What an account turned out to be, or why we could not find out. */
    public record ResolvedAccount(String accountNumber, String bankCode, String holderName,
                                  String failure) {

        public boolean resolved() {
            return failure == null;
        }
    }

    /**
     * Resolves a destination account to the name it is held in.
     *
     * <p>Quick timeout: nobody is standing at a handset for this, and a validation that hangs should
     * fail fast so the person waiting to approve a transfer is told something.
     *
     * @param bankCode the recipient's bank identifier — Co-op's own is {@code 11}
     */
    public ResolvedAccount validate(com.hodi.modules.payments.PaymentType enquiry,
                                    String accountNumber, String bankCode) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return new ResolvedAccount(null, bankCode, null, "Enter the account number to send to.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("MessageReference", com.hodi.common.util.RrnGenerator.generate("VA"));
        body.put("UserID", configs.getString(ConfigKey.COOP_USER_ID));
        body.put("AccountNumber", accountNumber.trim());
        // Four digits in Co-op's own example — "0011" for Co-op itself — so it is padded rather than
        // trusted to arrive that way from a form.
        body.put("RecipientBankIdentifier", padded(bankCode));

        CoopClient.Outcome<Map<String, Object>> answer = coop.post(enquiry, body, false);
        if (!answer.succeeded()) {
            return new ResolvedAccount(accountNumber, bankCode, null, answer.failure());
        }

        Map<String, Object> response = answer.value();
        if (!CoopClient.succeeded(response)) {
            return new ResolvedAccount(accountNumber, bankCode, null,
                    "Co-op could not confirm that account: " + CoopAnswer.description(response));
        }

        // "RecipientName" is what Co-op's own example answers with; the others are kept for a bank that
        // renames a field between sandbox and production, which banks do.
        String name = text(response, "RecipientName", "recipientName", "AccountName", "accountName",
                "CustomerName", "customerName", "AccountHolderName");
        if (name == null) {
            /*
             * The bank said yes and named nobody.
             *
             * Treated as a failure, because the name is the entire point of asking. Approving a
             * transfer against a blank name is approving an account number, which is what validation
             * exists to stop somebody doing.
             */
            return new ResolvedAccount(accountNumber, bankCode, null,
                    "Co-op confirmed the account but returned no name, so there is nothing to check "
                            + "against. Confirm the details with the bank before sending.");
        }
        return new ResolvedAccount(accountNumber, padded(bankCode), name, null);
    }

    /**
     * Sends money to one account over PesaLink.
     *
     * <p>Patient timeout: this is money moving, and a read timeout short enough for an enquiry would
     * abandon exactly the transfers that go on to succeed — leaving the platform with no record of a
     * payment the bank made.
     *
     * @param reference ours, and what the callback and any later status enquiry quote
     */
    public CoopClient.Outcome<Map<String, Object>> send(com.hodi.modules.payments.PaymentType channel,
                                                        String reference, String fromAccount,
                                                        ResolvedAccount to, BigDecimal amount,
                                                        String narration, String callbackUrl) {
        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("ReferenceNumber", reference + "_1");
        destination.put("AccountNumber", to.accountNumber());
        destination.put("BankCode", unpadded(to.bankCode()));
        destination.put("Amount", amount);
        destination.put("TransactionCurrency", "KES");
        destination.put("Narration", narration);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("AccountNumber", fromAccount);
        source.put("Amount", amount);
        source.put("TransactionCurrency", "KES");
        source.put("Narration", narration);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("MessageReference", reference);
        body.put("CallBackUrl", callbackUrl);
        body.put("ISO2CountryCode", "KE");
        body.put("Source", source);
        body.put("Destinations", List.of(destination));

        log.info("Sending {} to {} at bank {} as {}", amount, to.accountNumber(),
                to.bankCode(), reference);
        // Through the channel, because sending money is a method with its own endpoint — unlike the
        // enquiries below, which belong to no method and ask about something that already happened.
        return coop.post(channel, body, true);
    }

    /** What became of a transfer, when no callback arrived. */
    public CoopClient.Outcome<Map<String, Object>> statusOf(
            com.hodi.modules.payments.PaymentType enquiry, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("MessageReference", reference);
        body.put("UserID", configs.getString(ConfigKey.COOP_USER_ID));
        return coop.post(enquiry, body, false);
    }

    /** Co-op's validation call wants four digits; their transfer call wants two. */
    private static String padded(String bankCode) {
        String digits = bankCode == null || bankCode.isBlank() ? "11" : bankCode.trim();
        return digits.length() >= 4 ? digits : "0".repeat(4 - digits.length()) + digits;
    }

    private static String unpadded(String bankCode) {
        String digits = padded(bankCode).replaceFirst("^0+", "");
        return digits.isEmpty() ? "11" : digits;
    }

    private static String text(Map<String, Object> body, String... names) {
        for (String name : names) {
            Object value = body.get(name);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }
}
