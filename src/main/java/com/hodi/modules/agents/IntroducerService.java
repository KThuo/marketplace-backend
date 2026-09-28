package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Who brought the buyer: the one place that says which agents may be named, and how early.
 *
 * <p>An introducer is an APPROVED agent, named by reference. Enquiries, offers and bookings all name one
 * through here, so they agree about what counts. How early a name may be given is the bank's setting
 * ({@code commission.agent.attribution.from}): from the first enquiry, from the offer, or only on the
 * booking. Later stages are always open — a booking may always say who brought its buyer — and a name
 * given early is carried forward, so nobody has to remember it at the end.
 */
@Service
@RequiredArgsConstructor
public class IntroducerService {

    public static final String FROM_ENQUIRY = "ENQUIRY";
    public static final String FROM_OFFER = "OFFER";
    public static final String FROM_BOOKING = "BOOKING";

    private final AgentProfileRepository agents;
    private final ConfigurationService configs;

    /** An agent as a picker shows them. */
    public record AgentOption(String reference, String fullName, String agencyName) {}

    /**
     * What a picker needs: the agents that may be named, and the earliest stage at which naming is offered.
     */
    public record IntroducerOptions(String attributionFrom, List<AgentOption> agents) {}

    /** The earliest stage an introducer may be named at, as configured. Anything unrecognised means ENQUIRY. */
    public String attributionFrom() {
        String raw = configs.getString(ConfigKey.AGENT_ATTRIBUTION_FROM);
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return FROM_OFFER.equals(v) || FROM_BOOKING.equals(v) ? v : FROM_ENQUIRY;
    }

    /** Whether naming an introducer is offered at this stage under the current setting. */
    public boolean allowedAt(String stage) {
        return allowedAt(stage, attributionFrom());
    }

    /** The rule itself, with the setting passed in: a later stage is always open. */
    static boolean allowedAt(String stage, String from) {
        return rank(stage) >= rank(from);
    }

    /** Refuses a name given earlier than the bank allows, and says which setting decides. */
    public void assertAllowedAt(String stage) {
        assertAllowedAt(stage, attributionFrom());
    }

    static void assertAllowedAt(String stage, String from) {
        if (allowedAt(stage, from)) return;
        throw new HodiException("Who brought the buyer is named from the " + from.toLowerCase(Locale.ROOT)
                + " on, under this bank's settings — not on " + article(stage) + ".", HttpStatus.CONFLICT);
    }

    /**
     * The agent a reference names, if it names an approved one. Blank means nobody, and is how a name is
     * cleared.
     */
    @Transactional(readOnly = true)
    public Optional<AgentProfile> resolve(String agentRef) {
        if (agentRef == null || agentRef.isBlank()) return Optional.empty();
        AgentProfile agent = agents.findByReference(agentRef.trim())
                .filter(a -> a.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", agentRef));
        if (!AgentState.APPROVED.equals(agent.getState())) {
            throw new HodiException("Only an approved agent can be named as having brought the buyer; "
                    + agent.getFullName() + " is " + agent.getState().toLowerCase(Locale.ROOT) + ".",
                    HttpStatus.CONFLICT);
        }
        return Optional.of(agent);
    }

    /** The name behind an id, for a response; null when there is none or the agent is gone. */
    @Transactional(readOnly = true)
    public Optional<AgentProfile> byId(Long agentId) {
        return agentId == null ? Optional.empty() : agents.findById(agentId);
    }

    /** Every approved agent, for a picker. A short list today; searched on the server if it ever is not. */
    @Transactional(readOnly = true)
    public IntroducerOptions options() {
        List<AgentOption> list = agents.findApproved().stream()
                .map(a -> new AgentOption(a.getReference(), a.getFullName(),
                        a.isSelfEmployed() ? null : a.getAgencyName()))
                .toList();
        return new IntroducerOptions(attributionFrom(), list);
    }

    private static int rank(String stage) {
        return switch (stage == null ? "" : stage.toUpperCase(Locale.ROOT)) {
            case FROM_ENQUIRY -> 0;
            case FROM_OFFER -> 1;
            default -> 2;
        };
    }

    private static String article(String stage) {
        return switch (stage == null ? "" : stage.toUpperCase(Locale.ROOT)) {
            case FROM_ENQUIRY -> "an enquiry";
            case FROM_OFFER -> "an offer";
            default -> "a booking";
        };
    }
}
