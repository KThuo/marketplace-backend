package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.assistant.AssistantProvider.Action;
import com.hodi.modules.assistant.AssistantProvider.Link;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.leads.EnquiryService;
import com.hodi.modules.leads.LeadDtos;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Conversations with the assistant, for signed-in buyers and for visitors (M11).
 *
 * <h2>Two kinds of owner</h2>
 *
 * <p>A signed-in person owns a conversation by user id. A visitor owns one by a random token their browser
 * keeps and sends in a header; only its SHA-256 is stored, so the table cannot be used to resume anybody's
 * conversation. When the visitor signs in, {@link #claim} moves their conversations onto the account, and the
 * conversation carries on where it was, now able to answer about their own bookings.
 *
 * <h2>Visitors are limited</h2>
 *
 * <p>The public endpoint answers searches, so without a limit it is a way to copy the listings. Questions are
 * counted per token and per address (a keyed hash of it, never the address itself) over the last hour.
 *
 * <h2>The transcript is the point of the hand-off</h2>
 *
 * <p>A hand-off raises an ordinary enquiry with the conversation quoted: to the listing's seller when it is
 * about a home, otherwise wherever the platform's setting says. The person replying reads what was asked and
 * what the assistant said, so nobody has to say it twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM HH:mm");

    /** Said once, at the top of every conversation, so nobody mistakes it for a person. Named from settings. */
    private String opening() {
        return "I am " + brand.assistant() + ", " + brand.name() + "'s assistant: software, not a person. I can search "
                + "the listings, tell you what is coming up at auction, say how many homes are left in a development, work out roughly what you could borrow, "
                + "explain the words that come up when you are buying, and tell you where your own bookings, enquiries "
                + "and viewings stand.\n\nIf I am not helping, say \"talk to a person\" and I will pass the whole "
                + "conversation on, so you do not have to say it twice.";
    }
    @Value("${hodi.jwt.secret}")
    private String secret;

    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final AssistantProvider provider;
    private final EnquiryService enquiries;
    private final TenantRepository tenants;
    private final ConfigurationService configs;
    private final AuditService audit;
    private final ObjectMapper json;
    private final com.hodi.modules.configurations.Brand brand;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * @param id      for rating it; present on the assistant's side only
     * @param actions what can be done from here; only on the latest answer of an open conversation
     * @param rating  1, -1 or null
     */
    public record MessageResponse(String id, String side, String body, String intent, List<Link> links,
                                  List<Action> actions, Short rating, OffsetDateTime at) {}

    public record ConversationResponse(String reference, String title, String state, String enquiryRef,
                                       int messageCount, OffsetDateTime lastMessageAt, OffsetDateTime createdAt) {}

    /** @param generalHandOff whether a person may be asked about something other than a listing */
    public record ConversationDetail(ConversationResponse conversation, List<MessageResponse> messages,
                                     boolean generalHandOff) {}

    public record AskRequest(
            /** Null starts a new conversation. */
            String conversationReference,
            @NotBlank(message = "Type something") @Size(max = 2000, message = "That is too long for me") String message) {}

    /** @param propertyReference the listing it is about, or null for a general question */
    public record HandOffRequest(String propertyReference, @Size(max = 2000) String message) {}

    public record RateRequest(@Min(-1) @Max(1) int rating) {}

    public record ClaimRequest(@NotBlank String visitorToken) {}

    public record ClaimResponse(int claimed) {}

    public record IntentTally(String intent, long answers, long liked, long disliked) {}

    public record FailingAnswer(String conversationReference, String question, String answer, String intent,
                                Short rating, boolean visitor, OffsetDateTime at) {}

    public record Insights(int days, long answers, long unanswered, long disliked, List<IntentTally> byIntent,
                           PagedResponse<FailingAnswer> failing) {}

    /** Who is talking: a user, or a visitor's hashed token and hashed address. */
    private record Caller(Long userId, String visitorHash, String clientHash) {
        boolean visitor() { return userId == null; }
    }

    // ── talking ───────────────────────────────────────────────────────────────

    @Transactional
    public ConversationDetail ask(AskRequest request) {
        return converse(new Caller(AuthContext.requireUserId(), null, null), request);
    }

    @Transactional
    public ConversationDetail askAsVisitor(String visitorToken, String clientAddress, AskRequest request) {
        if (!configs.getBoolean(ConfigKey.ASSISTANT_VISITORS_ENABLED)) {
            throw new HodiException("Sign in to use the assistant.", HttpStatus.FORBIDDEN);
        }
        Caller caller = visitor(visitorToken, clientAddress);
        int limit = configs.getInt(ConfigKey.ASSISTANT_VISITOR_HOURLY_LIMIT, 40);
        long asked = messages.countVisitorQuestionsSince(caller.visitorHash(), caller.clientHash(),
                OffsetDateTime.now().minusHours(1));
        if (limit > 0 && asked >= limit) {
            throw new HodiException("That is a lot of questions in an hour. Sign in to carry on, or try again later.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }
        return converse(caller, request);
    }

    private ConversationDetail converse(Caller caller, AskRequest request) {
        AssistantConversation conversation = request.conversationReference() == null
                || request.conversationReference().isBlank()
                ? start(caller, request.message())
                : requireOwned(request.conversationReference(), caller);
        if (!conversation.isOpen()) {
            throw new HodiException("That conversation has been passed to a person. Carry on with them, or start "
                    + "a new one.", HttpStatus.CONFLICT);
        }
        if (caller.visitor() && caller.clientHash() != null) conversation.setClientHash(caller.clientHash());

        record(conversation, AssistantConstants.SIDE_USER, request.message().trim(), null, null);
        AssistantProvider.Answer answer = provider.answer(new AssistantProvider.Question(
                request.message(), caller.userId(), contextOf(conversation)));
        Map<String, Object> payload = new LinkedHashMap<>();
        if (answer.data() != null) payload.put("data", answer.data());
        payload.put("links", answer.links() == null ? List.of() : answer.links());
        payload.put("actions", answer.actions() == null ? List.of() : answer.actions());
        conversation.setContext(write(answer.context()));
        record(conversation, AssistantConstants.SIDE_ASSISTANT, answer.reply(), answer.intent(), write(payload));
        return detail(conversation);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ConversationResponse> mine(PagedDataRequest request) {
        var page = conversations.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ConversationDetail find(String reference) {
        return detail(requireOwned(reference, new Caller(AuthContext.requireUserId(), null, null)));
    }

    @Transactional(readOnly = true)
    public ConversationDetail findAsVisitor(String visitorToken, String reference) {
        return detail(requireOwned(reference, visitor(visitorToken, null)));
    }

    // ── rating an answer ──────────────────────────────────────────────────────

    @Transactional
    public ConversationDetail rate(String reference, String messageId, RateRequest request) {
        return rate(new Caller(AuthContext.requireUserId(), null, null), reference, messageId, request);
    }

    @Transactional
    public ConversationDetail rateAsVisitor(String visitorToken, String reference, String messageId, RateRequest request) {
        return rate(visitor(visitorToken, null), reference, messageId, request);
    }

    /** 1, -1, or 0 to take a rating back. Only an answer, and only in the rater's own conversation. */
    private ConversationDetail rate(Caller caller, String reference, String messageId, RateRequest request) {
        AssistantConversation conversation = requireOwned(reference, caller);
        Long id;
        try {
            id = HashIdUtil.decodeId(messageId);
        } catch (RuntimeException e) {
            id = null;
        }
        AssistantMessage message = id == null ? null : messages.findById(id).orElse(null);
        if (message == null || !message.getConversationId().equals(conversation.getId())
                || !AssistantConstants.SIDE_ASSISTANT.equals(message.getSide())) {
            throw new ResourceNotFoundException("Answer", messageId);
        }
        message.setRating(request.rating() == 0 ? null : (short) request.rating());
        message.setRatedAt(request.rating() == 0 ? null : OffsetDateTime.now());
        messages.save(message);
        return detail(conversation);
    }

    // ── signing in ────────────────────────────────────────────────────────────

    /** Moves the visitor's conversations onto the signed-in account. Harmless to repeat. */
    @Transactional
    public ClaimResponse claim(ClaimRequest request) {
        String hash = sha256(token(request.visitorToken()));
        int claimed = conversations.claim(hash, AuthContext.requireUserId(), AuthContext.username());
        if (claimed > 0) {
            audit.record(AppConstant.ACTION_UPDATE, "AssistantConversation", null, null,
                    claimed + " visitor conversation" + (claimed == 1 ? "" : "s") + " kept on signing in");
        }
        return new ClaimResponse(claimed);
    }

    // ── giving up on it ───────────────────────────────────────────────────────

    /**
     * Hands the conversation to a person.
     *
     * <p>About a listing, it goes to that listing's seller. About nothing in particular, it goes where
     * {@code assistant.handoff.general} says: the platform's own staff, a named organisation, or nowhere, in
     * which case the person is told to ask from the listing. A named organisation that is missing or not active
     * falls back to the platform, so nobody is left talking to an empty inbox.
     */
    @Transactional
    public ConversationDetail handOff(String reference, HandOffRequest request) {
        AssistantConversation conversation = requireOwned(reference, new Caller(AuthContext.requireUserId(), null, null));
        if (!conversation.isOpen()) {
            throw new HodiException("That one has already been passed on.", HttpStatus.CONFLICT);
        }
        String opening = (request.message() == null || request.message().isBlank()
                ? "I was using the assistant and would rather speak to somebody."
                : request.message().trim())
                + "\n\n— what I asked the assistant —\n" + transcriptOf(conversation);

        LeadDtos.EnquiryResponse enquiry;
        String who;
        if (request.propertyReference() != null && !request.propertyReference().isBlank()) {
            enquiry = enquiries.raise(new LeadDtos.RaiseEnquiryRequest(
                    request.propertyReference().trim(), "Passed on from the assistant", opening, null, null));
            who = enquiry.sellerName() == null ? "The seller" : enquiry.sellerName();
        } else {
            String target = generalTarget();
            if (AssistantConstants.HANDOFF_OFF.equals(target)) {
                throw new HodiException("A person can be asked only about a particular home. Open the listing and "
                        + "ask from there.", HttpStatus.CONFLICT);
            }
            Tenant organisation = AssistantConstants.HANDOFF_ORGANISATION.equals(target) ? namedOrganisation() : null;
            enquiry = enquiries.raiseGeneral(organisation == null ? null : organisation.getId(),
                    organisation == null ? null : organisation.getName(), "Passed on from the assistant", opening);
            who = organisation == null ? brand.team() : organisation.getName();
        }

        conversation.setState(AssistantConstants.STATE_HANDED_OFF);
        conversation.setEnquiryRef(enquiry.reference());
        conversation.setHandedOffAt(OffsetDateTime.now());
        conversation.setUpdatedBy(AuthContext.username());
        conversations.save(conversation);

        record(conversation, AssistantConstants.SIDE_ASSISTANT,
                "Passed on. " + who + " has the whole conversation and will reply to you under your enquiries, "
                        + "reference " + enquiry.reference() + ".",
                AssistantConstants.INTENT_HANDOFF,
                write(Map.of("links", List.of(new Link("Your conversations", "/account/conversations")),
                        "actions", List.of())));

        audit.record(AppConstant.ACTION_REQUEST, "AssistantConversation", conversation.getId(), null,
                "handed off as " + enquiry.reference());
        log.info("Conversation {} handed off as enquiry {}", conversation.getReference(), enquiry.reference());
        return detail(conversation);
    }

    @Transactional
    public void close(String reference) {
        AssistantConversation conversation = requireOwned(reference, new Caller(AuthContext.requireUserId(), null, null));
        conversation.setState(AssistantConstants.STATE_CLOSED);
        conversation.setUpdatedBy(AuthContext.username());
        conversations.save(conversation);
    }

    // ── what it could not do ──────────────────────────────────────────────────

    /**
     * How the assistant is doing over the last {@code days}: answers by intent, and the questions it could not
     * answer or answered badly, each with the question that was asked. What to add to the knowledge comes from
     * reading the second list.
     */
    @Transactional(readOnly = true)
    public Insights insights(int days, PagedDataRequest request) {
        int window = Math.max(1, Math.min(days, 365));
        OffsetDateTime since = OffsetDateTime.now().minusDays(window);
        List<IntentTally> tallies = messages.tallyByIntent(since).stream()
                .map(r -> new IntentTally((String) r[0], num(r[1]), num(r[2]), num(r[3])))
                .sorted(Comparator.comparingLong(IntentTally::answers).reversed())
                .toList();
        long answers = tallies.stream().mapToLong(IntentTally::answers).sum();
        long unanswered = tallies.stream().filter(t -> AssistantConstants.INTENT_UNKNOWN.equals(t.intent()))
                .mapToLong(IntentTally::answers).sum();
        long disliked = tallies.stream().mapToLong(IntentTally::disliked).sum();

        int size = request.getSize() == null || request.getSize() <= 0 ? 20 : Math.min(request.getSize(), 100);
        int pageNo = request.getPage() == null ? 0 : Math.max(request.getPage(), 0);
        var page = messages.findFailingSince(since, PageRequest.of(pageNo, size));
        Map<Long, AssistantConversation> owners = new HashMap<>();
        var failing = PagedResponse.from(page, m -> {
            AssistantConversation c = owners.computeIfAbsent(m.getConversationId(),
                    id -> conversations.findById(id).orElse(null));
            String asked = messages.findFirstByConversationIdAndSideAndCreatedAtLessThanEqualAndIdLessThanOrderByIdDesc(
                    m.getConversationId(), AssistantConstants.SIDE_USER, m.getCreatedAt(), m.getId())
                    .map(AssistantMessage::getBody).orElse("");
            return new FailingAnswer(c == null ? null : c.getReference(), asked, m.getBody(), m.getIntent(),
                    m.getRating(), c != null && c.getUserId() == null, m.getCreatedAt());
        });
        return new Insights(window, answers, unanswered, disliked, tallies, failing);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private AssistantConversation start(Caller caller, String firstMessage) {
        AssistantConversation conversation = conversations.save(AssistantConversation.builder()
                .reference(RrnGenerator.generate("AS"))
                .userId(caller.userId())
                .visitorHash(caller.visitorHash())
                .clientHash(caller.clientHash())
                .title(title(firstMessage))
                .state(AssistantConstants.STATE_OPEN)
                .createdBy(caller.visitor() ? "visitor" : AuthContext.username())
                .build());
        // A message rather than a client-side string, so the transcript a person reads begins with what was said.
        record(conversation, AssistantConstants.SIDE_ASSISTANT, opening(), null, null);
        return conversation;
    }

    private void record(AssistantConversation conversation, String side, String body, String intent, String payload) {
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

    /** What the person replying reads. Plain text, attributed, in order. */
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

    /** The same "not found" for somebody else's conversation as for one that does not exist. */
    private AssistantConversation requireOwned(String reference, Caller caller) {
        AssistantConversation c = conversations.findByReference(reference == null ? "" : reference.trim())
                .filter(x -> x.getStatus() == null || x.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", reference));
        boolean mine = caller.visitor()
                ? c.getUserId() == null && caller.visitorHash().equals(c.getVisitorHash())
                : caller.userId().equals(c.getUserId());
        if (!mine) throw new ResourceNotFoundException("Conversation", reference);
        return c;
    }

    private ConversationDetail detail(AssistantConversation conversation) {
        List<AssistantMessage> all = messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        int lastAnswer = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (AssistantConstants.SIDE_ASSISTANT.equals(all.get(i).getSide())) { lastAnswer = i; break; }
        }
        List<MessageResponse> out = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            AssistantMessage m = all.get(i);
            boolean assistant = AssistantConstants.SIDE_ASSISTANT.equals(m.getSide());
            Stored stored = read(m.getPayload());
            boolean live = i == lastAnswer && conversation.isOpen();
            out.add(new MessageResponse(assistant ? HashIdUtil.encodeId(m.getId()) : null, m.getSide(), m.getBody(),
                    m.getIntent(), stored.links(), live ? stored.actions() : List.of(), m.getRating(), m.getCreatedAt()));
        }
        return new ConversationDetail(toResponse(conversation), out,
                !AssistantConstants.HANDOFF_OFF.equals(generalTarget()));
    }

    private ConversationResponse toResponse(AssistantConversation c) {
        return new ConversationResponse(c.getReference(), c.getTitle(), c.getState(), c.getEnquiryRef(),
                c.getMessageCount(), c.getLastMessageAt(), c.getCreatedAt());
    }

    private String generalTarget() {
        String t = configs.getString(ConfigKey.ASSISTANT_HANDOFF_GENERAL);
        t = t == null ? "" : t.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case AssistantConstants.HANDOFF_OFF, AssistantConstants.HANDOFF_ORGANISATION -> t;
            default -> AssistantConstants.HANDOFF_PLATFORM;
        };
    }

    private Tenant namedOrganisation() {
        String ref = configs.getString(ConfigKey.ASSISTANT_HANDOFF_ORGANISATION);
        if (ref == null || ref.isBlank()) {
            log.warn("assistant.handoff.general is ORGANISATION but no organisation is named; sending to the platform");
            return null;
        }
        Tenant t = tenants.findByTenantRef(ref.trim()).orElse(null);
        if (t == null || t.getStatus() == null || t.getStatus() != AppConstant.STATUS_ACTIVE
                || t.getSuspendedAt() != null || t.getTerminatedAt() != null) {
            log.warn("Hand-off organisation {} is missing or not active; sending to the platform", ref);
            return null;
        }
        return t;
    }

    // ── visitors ──────────────────────────────────────────────────────────────

    private Caller visitor(String token, String clientAddress) {
        String hash = sha256(token(token));
        String client = clientAddress == null || clientAddress.isBlank() ? null : keyed(clientAddress.trim());
        return new Caller(null, hash, client);
    }

    /** A visitor's token: generated by their browser, long and random. Anything else is refused. */
    private static String token(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{24,128}")) {
            throw new HodiException("This browser has no assistant session. Reload the page and try again.",
                    HttpStatus.BAD_REQUEST);
        }
        return token;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash", e);
        }
    }

    /** An address hashed with the platform's secret, because a plain hash of an IPv4 address is reversible. */
    private String keyed(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("assistant-client:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash", e);
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    private record Stored(List<Link> links, List<Action> actions) {}

    private AssistantContext contextOf(AssistantConversation c) {
        if (c.getContext() == null || c.getContext().isBlank()) return AssistantContext.empty();
        try {
            return json.readValue(c.getContext(), AssistantContext.class);
        } catch (RuntimeException e) {
            log.debug("Conversation {} had a context that no longer reads; starting afresh", c.getReference());
            return AssistantContext.empty();
        }
    }

    /** Links and actions from what an answer stored. Older answers kept only their findings, and have neither. */
    private Stored read(String payload) {
        if (payload == null || payload.isBlank()) return new Stored(List.of(), List.of());
        try {
            Map<String, Object> map = json.readValue(payload, new TypeReference<Map<String, Object>>() {});
            List<Link> links = map.get("links") == null ? List.of()
                    : json.convertValue(map.get("links"), new TypeReference<List<Link>>() {});
            List<Action> actions = map.get("actions") == null ? List.of()
                    : json.convertValue(map.get("actions"), new TypeReference<List<Action>>() {});
            return new Stored(links, actions);
        } catch (RuntimeException e) {
            return new Stored(List.of(), List.of());
        }
    }

    private String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /** The first thing they said, trimmed to something a list can show. */
    private static String title(String message) {
        String trimmed = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return "A question";
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "…";
    }
}
