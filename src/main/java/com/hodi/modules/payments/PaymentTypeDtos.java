package com.hodi.modules.payments;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** The channels, and the accounts configured on them. */
public final class PaymentTypeDtos {

    private PaymentTypeDtos() {}

    // ── the catalogue ─────────────────────────────────────────────────────────

    /**
     * A channel as a screen reads it.
     *
     * @param renderAs which form a pay screen should show. Composed here so no screen re-encodes the
     *                 classification
     * @param inUse    how many accounts are configured on it within the caller's own scope, so retiring one
     *                 can say what it breaks
     */
    public record ChannelResponse(
            String id,
            String code,
            String name,
            String description,
            String providerName,
            String providerType,
            String category,
            String renderAs,
            String method,
            String methodLabel,
            boolean electronic,
            boolean accountBased,
            boolean requiresShortCode,
            int sortOrder,
            Integer status,
            String statusFlag,
            long inUse,
            /**
             * Whether this channel has everything it needs to be called.
             *
             * <p>On the list rather than only inside the configure form, because "why did nothing happen"
             * is asked of the list. A channel switched on but with no endpoint looks available and is not.
             */
            boolean configured,
            /** What is still missing, by label, so the row can say so without opening anything. */
            java.util.List<String> missingConfig) {}

    /** Rename or re-describe a channel. Its behaviour is not editable — that is what the code is for. */
    public record UpdateChannelRequest(
            @NotBlank(message = "Name this method") @Size(max = 120) String name,
            @Size(max = 250) String description,
            Integer sortOrder) {}

    @Getter @Setter
    public static class ChannelListRequest extends PagedDataRequest {
        private String category;
    }

    // ── the accounts ──────────────────────────────────────────────────────────

    /**
     * A configured account.
     *
     * @param ownerKind        TENANT, INSTITUTION or PLATFORM — which kind of principal collects into it
     * @param developmentLabel "All developments" or the development's name, composed once here rather than
     *                         left to each screen
     */
    public record AccountResponse(
            String id,
            String paymentTypeId,
            String name,
            String providerName,
            String category,
            String renderAs,
            String method,
            String ownerKind,
            String ownerName,
            String tenantId,
            String institutionId,
            String developmentId,
            String developmentLabel,
            String payBillNo,
            String accountNo,
            String accountName,
            String shortCode,
            /**
             * The channel's fields with this account's values — a secret reading back as a mask, which sent
             * back unchanged on a save means "leave it alone".
             */
            java.util.List<ChannelConfig.Field> config,
            String accountsLabel,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt,
            String createdBy,
            OffsetDateTime updatedAt,
            String updatedBy) {}

    /**
     * Assign or edit an account.
     *
     * <p>The channel decides which of these are required, so the record cannot: a phone prompt has no account
     * number and an inbound credit cannot do without one. The service reads the catalogue row and enforces
     * it, which is also where the message can name the channel.
     *
     * <p>{@code tenantId} and {@code institutionId} are read only from platform staff. Everybody else's
     * organisation is derived from their session, because a form field that accepts it is a form field
     * somebody can change.
     */
    public record SaveAccountRequest(
            @NotBlank(message = "Choose the payment method") String paymentTypeId,
            String tenantId,
            String institutionId,
            /** Null for every development the owner has. */
            String developmentId,
            @Size(max = 32, message = "That paybill is too long") String payBillNo,
            @Size(max = 64, message = "That account number is too long") String accountNo,
            @Size(max = 160, message = "That account name is too long") String accountName,
            @Size(max = 64, message = "That short code is too long") String shortCode,
            /** The descriptor's fields by key. Undeclared keys are dropped rather than stored. */
            java.util.Map<String, Object> config,
            /** The challenge the code belongs to, from the OTP request. */
            String challengeToken,
            /** The code texted to the organisation. Refused without it, from one place with one message. */
            String otp) {}

    /**
     * A channel this owner could still be given, for the form's select.
     *
     * <p>Carries what the form must ask for and nothing about what is absent from the list.
     */
    public record AssignableChannel(
            String id,
            String name,
            String description,
            String providerName,
            String category,
            String method,
            boolean accountBased,
            boolean requiresShortCode,
            /** What an account of this channel asks for — rendered by the form, empty when it asks nothing. */
            java.util.List<ChannelConfig.Field> accountFields,
            /** What one of them is called: "Biller" on a Co-op biller, "Account" everywhere else. */
            String accountsLabel) {}

    /**
     * An account the receive form may name for a booking.
     *
     * <p>Deliberately narrow: the account's own id, its channel and the numbers a person would recognise it
     * by. Choosing one fixes the payment's method from the channel.
     */
    /**
     * A channel's configuration as the screen needs it: the fields it declares, with values.
     *
     * <p>Secrets read back as a mask and are sent back unchanged to mean "leave it alone" — see
     * {@link ChannelConfig}. {@code missing} names the required fields still empty, so "not configured
     * yet" is something an operator can act on rather than a state they have to deduce.
     */
    public record ChannelConfiguration(
            String id,
            String code,
            String name,
            String providerName,
            java.util.List<ChannelConfig.Field> fields,
            java.util.List<String> missing,
            boolean ready) {}

    /** What a save sends: key to value, with a masked secret meaning "unchanged". */
    public record SaveChannelConfiguration(java.util.Map<String, Object> values) {}

    /**
     * What the set-up form needs to know before it asks anything.
     *
     * <p>Chiefly whether an organisation may hold an account at all. While the platform collects everything,
     * the owner question has one answer, and a select offering two where one is refusable is a question the
     * form already knows the answer to.
     */
    public record AccountSetupContext(
            boolean organisationsMayCollect,
            boolean platformStaff) {}

    public record OfferedAccount(
            String id,
            String name,
            String providerName,
            String category,
            String renderAs,
            String method,
            String payBillNo,
            String accountNo,
            String developmentLabel) {}

    /** Ask for a code. The organisation named the way {@link SaveAccountRequest} names it. */
    public record OtpRequest(String tenantId, String institutionId) {}

    /** What the code step needs to explain the wait, plus the handle the save must carry back. */
    public record OtpIssued(String challengeToken, String sentTo, OffsetDateTime expiresAt, int validForMinutes) {}

    /** The account-number duplicate check the form runs before spending a code. */
    public record AccountCheck(boolean taken, String message) {}

    /** A development an account may be narrowed to, for the form's select. */
    public record DevelopmentOption(String id, String label) {}

    @Getter @Setter
    public static class AccountListRequest extends PagedDataRequest {
        /** TENANT, INSTITUTION or PLATFORM. */
        private String ownerKind;
        private String developmentId;
        private String paymentTypeId;
        private String category;
    }

    /** "All developments" or the development's name — one implementation, so no two screens differ. */
    public static String developmentLabel(PaymentAccount account, String developmentName) {
        if (account.isOwnerWide()) return "All developments";
        return developmentName == null ? "One development" : developmentName;
    }
}
