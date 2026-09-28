package com.hodi.modules.settlements;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** What settling a sale the bank collected looks like, to the bank and to the owner. */
public final class SettlementDtos {

    private SettlementDtos() {}

    /** Where a sale's settlement stands. */
    public static final String NOT_BANK_COLLECTED = "NOT_BANK_COLLECTED";
    public static final String AWAITING = "AWAITING_SETTLEMENT";
    public static final String IN_FLIGHT = "IN_FLIGHT";
    public static final String SETTLED = "SETTLED";

    /** One transfer of a settlement, as the panel lists it. */
    public record Leg(
            String kind,
            String disbursementId,
            String reference,
            String state,
            String stateLabel,
            BigDecimal amount,
            String payeeName,
            OffsetDateTime settledAt) {}

    /** An account the owner already has with us, offered as where the proceeds might go. */
    public record SuggestedAccount(String accountNo, String bankCode, String name) {}

    /** An agent's payout account, offered as where their fee goes. */
    public record AgentAccountOption(String id, String accountNo, String bankCode, String confirmedName,
                                     boolean defaultAccount) {}

    /**
     * The settlement of one sale: what came in, what comes off, what the owner gets — and what has been
     * done about it.
     *
     * @param blockers   what stops it being proposed today, in words; empty when nothing does
     * @param mayPropose this caller may propose it, and nothing blocks it
     */
    public record SettlementResponse(
            String bookingId,
            String bookingReference,
            String developmentId,
            String developmentName,
            String home,
            String buyerName,
            String ownerName,
            String currency,
            BigDecimal priceAgreed,
            BigDecimal gross,
            BigDecimal bankFee,
            BigDecimal agentFee,
            /** SELLER or BANK: whose money the agent's fee comes out of. Null when there is no agent line. */
            String agentFeeBorneBy,
            String agentName,
            /** What the bank keeps or moves: its fee, less the agent's when the bank bears that. */
            BigDecimal bankKeeps,
            BigDecimal netToOwner,
            /** RETAIN or TRANSFER, as configured today; copied onto the settlement by the legs it creates. */
            String feeSettlement,
            String state,
            OffsetDateTime completedAt,
            OffsetDateTime settledAt,
            List<Leg> legs,
            List<String> blockers,
            boolean mayPropose,
            List<SuggestedAccount> ownerAccounts,
            List<AgentAccountOption> agentAccounts) {}

    /**
     * Proposing the settlement. The owner's account is typed or picked and confirmed with the bank on the
     * way through; the agent's is one of their confirmed accounts, the default unless another is chosen.
     */
    public record SettleRequest(
            @Size(max = 8) String ownerBankCode,
            @Size(max = 32) String ownerAccountNo,
            String agentAccountId,
            @Size(max = 160) String narration) {}

    /** A row of the queue. */
    public record QueueRow(
            String bookingId,
            String bookingReference,
            String developmentName,
            String home,
            String buyerName,
            String ownerName,
            String currency,
            BigDecimal gross,
            BigDecimal bankFee,
            BigDecimal agentFee,
            BigDecimal netToOwner,
            String state,
            OffsetDateTime completedAt,
            OffsetDateTime settledAt) {}

    /** A development's sales settlements, totalled, for the owner's finance tab. */
    public record DevelopmentSettlements(
            String currency,
            int completedSales,
            int settledSales,
            int awaitingSettlement,
            BigDecimal gross,
            BigDecimal bankFees,
            BigDecimal agentFees,
            BigDecimal netToOwner,
            /** Proceeds the bank has confirmed paid to the owner. */
            BigDecimal paidToOwner,
            /** Net still with the bank: completed and not yet settled. */
            BigDecimal stillWithBank) {}
}
