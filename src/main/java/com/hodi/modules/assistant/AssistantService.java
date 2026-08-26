package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.leads.LeadDtos;
import com.hodi.modules.leads.EnquiryService;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Conversations with the assistant, and the hand-off to a person (M11).
 *
 * <h2>It says what it is</h2>
 *
 * <p>The opening line of every conversation is the assistant saying it is not a person and what it can
 * actually do. Somebody about to spend everything they have on a house should not have to work out whether
 * they are talking to staff.
 *
 * <h2>The transcript is the point of the hand-off</h2>
 *
 * <p>A buyer who gives up on a machine and asks for a person should not have to say it all again. {@link
 * #handOff} raises an ordinary M4 enquiry against a listing, with the whole conversation quoted in the first
 * message — so the seller opens one thing and sees the lot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm");

    /** Said once, at the top of every conversation. */
    private static final String OPENING = """
            I am Hodi's assistant — software, not a person. I can search the listings, work out roughly what \
            you could borrow, explain the words that come up when you are buying, and tell you where your own \
            enquiries and viewings stand.

            If I am not helping, say so and I will pass the whole conversation to a person, so you do not \
            have to say it twice.""";

    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final AssistantProvider provider;
    private final EnquiryService enquiries;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record MessageResponse(
            String side,
            String body,
            String intent,
            List<AssistantProvider.Link> links,
            OffsetDateTime at) {}

    public record ConversationResponse(
            String reference,
            String title,
            String state,
            String enquiryRef,
            int messageCount,
            OffsetDateTime lastMessageAt,
            OffsetDateTime createdAt) {}

    public record ConversationDetail(ConversationResponse conversation, List<MessageResponse> messages) {}

    public record AskRequest(
            /** Null starts a new conversation. */
            String conversationReference,
            @NotBlank(message = "Type something") @Size(max = 2000) String message) {}

    public record HandOffRequest(
            @NotBlank(message = "Which listing is it about?") String propertyReference,
            String message) {}

    // ── talking ───────────────────────────────────────────────────────────────

    @Transactional
    public ConversationDetail ask(AskRequest request) {
        Long userId = AuthContext.requireUserId();
        AssistantConversation conversation = request.conversationReference() == null
                || request.conversationReference().isBlank()
                ? start(userId, request.message())
                : requireMine(request.conversationReference());

        if (!conversation.isOpen()) {
            throw new HodiException(
                    "That conversation has been passed to a person — carry on with them, or start a new one.",
                    HttpStatus.CONFLICT);
        }

        record(conversation, AssistantConstants.SIDE_USER, request.message(), null, null);
        AssistantProvider.Answer answer = provider.answer(request.message(), userId);
        record(conversation, AssistantConstants.SIDE_ASSISTANT, answer.reply(), answer.intent(),
                answer.payload());

        return detail(conversation, answer.links());
    }

    @Transactional(readOnly = true)
    public PagedResponse<ConversationResponse> mine(PagedDataRequest request) {
        var page = conversations.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ConversationDetail find(String reference) {
        return detail(requireMine(reference), List.of());
    }

    // ── giving up on it ───────────────────────────────────────────────────────

    /**
     * Hands the conversation to a person.
     *
     * <p>An ordinary M4 enquiry, against a real listing, with the transcript quoted. Deliberately not a new
     * kind of ticket: the seller's team already has an inbox, and a second one for "assistant hand-offs"
     * would be a queue somebody has to remember to read.
     */
    @Transactional
    public ConversationDetail handOff(String reference, HandOffRequest request) {
        AssistantConversation conversation = requireMine(reference);
        if (!conversation.isOpen()) {
            throw new HodiException("That one has already been passed on.", HttpStatus.CONFLICT);
        }

        String transcript = transcriptOf(conversation);
        String opening = (request.message() == null || request.message().isBlank()
                ? "I was using the assistant and would rather speak to somebody."
                : request.message().trim())
                + "\n\n— what I asked the assistant —\n" + transcript;

        var enquiry = enquiries.raise(new LeadDtos.RaiseEnquiryRequest(
                request.propertyReference(),
                "Passed on from the assistant",
                opening,
                null, null));

        conversation.setState(AssistantConstants.STATE_HANDED_OFF);
        conversation.setEnquiryRef(enquiry.reference());
        conversation.setHandedOffAt(OffsetDateTime.now());
        conversation.setUpdatedBy(AuthContext.username());
        conversations.save(conversation);

        record(conversation, AssistantConstants.SIDE_ASSISTANT,
                "Passed on. " + enquiry.sellerName() + " has the whole conversation and will reply to you "
                        + "under your enquiries — reference " + enquiry.reference() + ".",
                AssistantConstants.INTENT_HANDOFF, null);

        audit.record(AppConstant.ACTION_REQUEST, "AssistantConversation", conversation.getId(), null,
                "handed off as " + enquiry.reference());
        log.info("Conversation {} handed off as enquiry {}", conversation.getReference(),
                enquiry.reference());
        return detail(conversation, List.of(
                new AssistantProvider.Link("Your conversations", "/account/conversations")));
    }

    @Transactional
    public void close(String reference) {
        AssistantConversation conversation = requireMine(reference);
        conversation.setState(AssistantConstants.STATE_CLOSED);
        conversation.setUpdatedBy(AuthContext.username());
        conversations.save(conversation);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private AssistantConversation start(Long userId, String firstMessage) {
        AssistantConversation conversation = conversations.save(AssistantConversation.builder()
                .reference(RrnGenerator.generate("AS"))
                .userId(userId)
                .title(title(firstMessage))
                .state(AssistantConstants.STATE_OPEN)
                .createdBy(AuthContext.username())
                .build());
        // The opening line is a message rather than a client-side string, so the transcript a seller reads
        // begins with what the buyer was actually told.
        record(conversation, AssistantConstants.SIDE_ASSISTANT, OPENING, null, null);
        return conversation;
    }

    private void record(AssistantConversation conversation, String side, String body,
                        String intent, String payload) {
        messages.save(AssistantMessage.builder()
                .conversationId(conversation.getId())
                .side(side)
                .body(body)
                .intent(intent)
                .payload(payload)
                .build());
        conversation.setMessageCount(conversation.getMessageCount() + 1);
        conversation.setLastMessageAt(OffsetDateTime.now());
        conversations.save(conversation);
    }

    /** What the seller reads. Plain text, attributed, in order. */
    private String transcriptOf(AssistantConversation conversation) {
        StringBuilder out = new StringBuilder();
        for (AssistantMessage m : messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId())) {
            out.append(AssistantConstants.SIDE_USER.equals(m.getSide()) ? "They asked" : "The assistant")
                    .append(" (").append(STAMP.format(m.getCreatedAt())).append("): ")
                    .append(m.getBody().replace("\n", " "))
                    .append('\n');
        }
        return out.toString().trim();
    }

    private AssistantConversation requireMine(String reference) {
        AssistantConversation conversation = conversations.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", reference));
        if (!conversation.getUserId().equals(AuthContext.requireUserId())) {
            throw new HodiException("That is not yours.", HttpStatus.FORBIDDEN);
        }
        return conversation;
    }

    private ConversationDetail detail(AssistantConversation conversation,
                                      List<AssistantProvider.Link> linksForLast) {
        List<AssistantMessage> all = messages.findByConversationIdOrderByCreatedAtAsc(
                conversation.getId());
        List<MessageResponse> out = new java.util.ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            AssistantMessage m = all.get(i);
            boolean last = i == all.size() - 1;
            out.add(new MessageResponse(m.getSide(), m.getBody(), m.getIntent(),
                    last ? linksForLast : List.of(), m.getCreatedAt()));
        }
        return new ConversationDetail(toResponse(conversation), out);
    }

    private ConversationResponse toResponse(AssistantConversation c) {
        return new ConversationResponse(c.getReference(), c.getTitle(), c.getState(), c.getEnquiryRef(),
                c.getMessageCount(), c.getLastMessageAt(), c.getCreatedAt());
    }

    /** The first thing they said, trimmed to something a list can show. */
    private static String title(String message) {
        String trimmed = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return "A question";
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "…";
    }
}
