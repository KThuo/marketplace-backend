package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import com.hodi.modules.leads.LeadDtos.MessageResponse;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The history a viewing or an offer keeps.
 *
 * <h2>The complaint this answers</h2>
 *
 * <p>"Conversation history not saved on the admin side." It was not: each lead held one note per role and
 * every decision overwrote the last, so a viewing rescheduled twice showed one reason and an offer
 * countered twice showed one. Nothing was lost in transit — it was never kept.
 *
 * <p>So every decision now appends rather than replaces. The single-column notes stay where they are and
 * keep their meaning ("the latest word"), because the seller's screens, the buyer's notification lines and
 * the calendar projection all read them; what changes is that the previous word survives beside it.
 *
 * <h2>Who is speaking</h2>
 *
 * <p>Taken from the caller's actor class rather than from which endpoint was hit, so a platform
 * administrator answering on a seller's behalf is recorded as {@code PLATFORM}. The buyer should see that
 * the bank replied rather than a name they never dealt with, and a thread that attributed it to the seller
 * would be a quietly false record of who said what.
 */
@Service
@RequiredArgsConstructor
public class LeadThreadService {

    private final LeadMessageRepository messages;

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Append one line, attributed to whoever is calling.
     *
     * <p>A blank body writes nothing: a decision taken without a note is still a decision, and a thread
     * padded with empty entries is harder to read than one with gaps.
     */
    @Transactional
    public void record(String leadType, Long leadId, String body, String stateAfter) {
        append(leadType, leadId, callerSide(), AuthContext.userId(), callerName(), body, stateAfter);
    }

    /** Append on the buyer's behalf — used where the buyer's own words arrive with the request. */
    @Transactional
    public void recordAsBuyer(String leadType, Long leadId, Long userId, String name,
                              String body, String stateAfter) {
        append(leadType, leadId, AppConstant.SIDE_BUYER, userId, name, body, stateAfter);
    }

    private void append(String leadType, Long leadId, String side, Long userId, String name,
                        String body, String stateAfter) {
        if (leadId == null || body == null || body.isBlank()) return;
        messages.save(LeadMessage.builder()
                .leadType(leadType)
                .leadId(leadId)
                .authorSide(side)
                .authorUserId(userId)
                .authorName(name)
                .body(body.trim())
                .stateAfter(stateAfter)
                .createdBy(AuthContext.username())
                .build());
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MessageResponse> thread(String leadType, Long leadId) {
        if (leadId == null) return List.of();
        return messages.thread(leadType, leadId).stream().map(LeadThreadService::toResponse).toList();
    }

    /**
     * Every thread for a page of leads, keyed by lead id.
     *
     * <p>A list of twenty viewings each fetching its own history is twenty-one queries for one screen.
     * The lists carry their threads because the admin side's whole complaint was that opening a row to
     * find out what was said is what nobody did.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<MessageResponse>> threads(String leadType, List<Long> leadIds) {
        if (leadIds == null || leadIds.isEmpty()) return Map.of();
        Map<Long, List<MessageResponse>> out = new LinkedHashMap<>();
        for (LeadMessage m : messages.threads(leadType, leadIds)) {
            out.computeIfAbsent(m.getLeadId(), k -> new ArrayList<>()).add(toResponse(m));
        }
        return out;
    }

    private static MessageResponse toResponse(LeadMessage m) {
        return new MessageResponse(m.getAuthorSide(), m.getAuthorName(), m.getBody(), m.getCreatedAt());
    }

    // ── who is calling ────────────────────────────────────────────────────────

    /**
     * Platform staff speak as the platform; everybody else on a seller's screen speaks as the seller.
     *
     * <p>{@code BUYER} is never returned here: the buyer's own messages arrive through the endpoints that
     * know it is the buyer, and inferring it from an actor class would make a buyer who also works for a
     * selling organisation ambiguous.
     */
    private String callerSide() {
        return AuthContext.current().filter(UserPrincipal::isPlatformStaff).isPresent()
                ? AppConstant.SIDE_PLATFORM
                : AppConstant.SIDE_SELLER;
    }

    private String callerName() {
        return AuthContext.current()
                .map(c -> c.getFullName() != null && !c.getFullName().isBlank()
                        ? c.getFullName() : c.getUsername())
                .orElse(null);
    }
}
