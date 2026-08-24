package com.hodi.modules.buyers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class BuyerDtos {

    private BuyerDtos() {}

    /**
     * @param password chosen by the buyer, and validated against the same policy as staff. Their account
     *                 holds their enquiries and their finance applications; there is no argument for a
     *                 weaker rule.
     */
    public record RegisterRequest(
            @NotBlank(message = "Enter your first name")
            @Size(max = 64, message = "That first name is too long") String firstName,

            @NotBlank(message = "Enter your last name")
            @Size(max = 64, message = "That last name is too long") String lastName,

            @NotBlank(message = "Enter your email address")
            @Email(message = "That does not look like an email address")
            @Size(max = 128, message = "That email address is too long") String email,

            @Size(max = 32, message = "That phone number is too long") String phone,

            @NotBlank(message = "Choose a password")
            @Size(max = 128, message = "That password is too long") String password,

            /**
             * Whether they asked to be told when a saved search finds something (plan §3.8).
             *
             * <p>Absent means no. A nullable Boolean rather than a primitive so that "they did not answer"
             * and "they answered no" arrive here as the same thing they were on the form — and so that a
             * client which forgets the field can never be read as consent. There is deliberately no
             * marketing equivalent: the registration form asks one specific question, and a second consent
             * bundled behind the first is not informed.
             */
            Boolean propertyAlertsOptIn) {}

    /**
     * @param channel {@code EMAIL} or {@code PHONE}. Names which channel is being confirmed rather than
     *                inferring it from the challenge, so a code sent to one cannot mark the other verified.
     */
    public record VerifyRequest(
            @NotBlank(message = "That request has expired. Start again.") String challengeToken,
            @NotBlank(message = "Enter the code") String code,
            String channel) {}

    public record ResendRequest(
            @NotBlank(message = "Enter your email address")
            @Email(message = "That does not look like an email address") String email) {}
}
