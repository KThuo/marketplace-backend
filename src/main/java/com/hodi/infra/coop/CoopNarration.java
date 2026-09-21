package com.hodi.infra.coop;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The one line Co-op writes about a credit, read into its parts.
 *
 * <h2>Three shapes, told apart by their shape</h2>
 *
 * <p>Co-op's notification carries the same JSON for every credit; what differs is the tilde-separated
 * {@code Narration}, and it differs by how the money was paid:
 *
 * <ul>
 *   <li><b>M-Pesa to the account (C2B)</b> — {@code receipt~phone~account~channel~name}:
 *       {@code TIP6V5IRAG~254707919065~01120000568900~MPESAC2B_400200~MELVIN WANJIKU}.</li>
 *   <li><b>M-Pesa paybill with an account number typed by the payer</b> — the second part carries
 *       {@code account#reference}: {@code UGRAA0WREC~1186059#B14~254720051193~MPESAC2B_400222~ERIC THUO}.
 *       The part after the hash is what the payer typed — on this platform, a booking's pay code.</li>
 *   <li><b>A prompt we sent (STK)</b>, four parts — {@code receipt~narration~phone~reference}, the
 *       reference being the one we put on the prompt.</li>
 *   <li><b>PesaLink</b> — {@code PESALINK~rrn~payer~source account~…}: there is no M-Pesa receipt, so the
 *       customer-facing reference is {@code PESALINK} joined to the RRN, as the bank prints it.</li>
 * </ul>
 *
 * <h2>Why the receipt matters</h2>
 *
 * <p>The first part is the M-Pesa receipt — the ten characters the payer sees on their phone and reads out
 * when they call. It is what the bank reference of a statement should be, so a slip can be validated on
 * the number the customer actually has. Co-op's own {@code TransactionId} is the bank's handle for the
 * posting and is kept beside it, not instead of it.
 *
 * <p>Positional, as the bank's own examples are, with the phone still found by shape as a fallback: the
 * order has already varied between examples, and a wrong position is worse than a missing field.
 */
public record CoopNarration(
        Format format,
        /** The customer-facing reference: the M-Pesa receipt, or PESALINK plus its RRN. */
        String receipt,
        String phone,
        String customerName,
        /** The account the payer named, where the narration names one apart from the credited account. */
        String account,
        /** What the payer typed as a reference, where the shape carries one: a pay code, our prompt's reference. */
        String quoted,
        String channel,
        List<String> parts) {

    public enum Format { C2B, C2B_ACCOUNT, STK, PESALINK, UNKNOWN }

    private static final CoopNarration NONE = new CoopNarration(Format.UNKNOWN, null, null, null, null, null, null, List.of());

    public static CoopNarration parse(String narration) {
        if (narration == null || narration.isBlank()) return NONE;
        // -1 keeps trailing empties, so a five-part line whose last field is blank is still five parts.
        List<String> parts = Arrays.stream(narration.split("~", -1)).map(String::trim).toList();

        if (parts.size() >= 4 && "PESALINK".equalsIgnoreCase(parts.get(0))) {
            String rest = parts.size() > 4
                    ? parts.subList(4, parts.size()).stream().filter(p -> !p.isBlank()).collect(Collectors.joining(" "))
                    : null;
            return new CoopNarration(Format.PESALINK, "PESALINK" + parts.get(1), null, blankToNull(parts.get(2)),
                    blankToNull(parts.get(3)), blankToNull(rest), "PESALINK", parts);
        }
        if (parts.size() == 4) {
            return new CoopNarration(Format.STK, blankToNull(parts.get(0)), blankToNull(parts.get(2)), null, null,
                    blankToNull(parts.get(3)), blankToNull(parts.get(1)), parts);
        }
        if (parts.size() >= 5 && parts.get(1).contains("#")) {
            String[] account = parts.get(1).split("#", 2);
            return new CoopNarration(Format.C2B_ACCOUNT, blankToNull(parts.get(0)), blankToNull(parts.get(2)),
                    blankToNull(parts.get(4)), blankToNull(account[0]), blankToNull(account.length > 1 ? account[1] : null),
                    blankToNull(parts.get(3)), parts);
        }
        if (parts.size() >= 5) {
            return new CoopNarration(Format.C2B, blankToNull(parts.get(0)), blankToNull(parts.get(1)),
                    blankToNull(parts.get(4)), blankToNull(parts.get(2)), null, blankToNull(parts.get(3)), parts);
        }
        return new CoopNarration(Format.UNKNOWN, null, null, null, null, null, null, parts);
    }

    public boolean known() {
        return format != Format.UNKNOWN;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
