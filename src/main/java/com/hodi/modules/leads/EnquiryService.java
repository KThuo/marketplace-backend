package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import com.hodi.modules.operations.AssignmentService;
import com.hodi.modules.operations.OperationsConstants;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.leads.LeadDtos.*;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Enquiries: a buyer asks, a seller answers (M4, BRD FR035–FR040).
 *
 * <h2>Two doors into one table</h2>
 *
 * <p>{@link #raise} and {@link #mine} are the buyer's, scoped by {@link AuthContext#requireUserId()}.
 * {@link #list} and {@link #reply} are the seller's, scoped by {@link TenantScope}. Neither reaches across:
 * a buyer's methods never take a tenant and a seller's never take a user id, so the two rules cannot be
 * confused for each other in a later refactor.
 *
 * <h2>The aggregates are maintained here, in the same transaction</h2>
 *
 * <p>{@code messageCount}, {@code lastMessageAt} and {@code awaitingSeller} are written every time a message
 * is added, by the one method that adds them. An inbox that sorted by a subquery over the message table
 * would be a join per row on the screen a seller keeps open all day.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnquiryService {

    private static final String REFERENCE_PREFIX = "EQ";

    private final EnquiryTicketRepository repository;
    private final com.hodi.modules.agents.IntroducerService introducers;
    private final AssignmentService assignment;
    private final EnquiryMessageRepository messages;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final AuditService audit;
    private final LeadNotifier notifier;

    // ── the buyer's side ──────────────────────────────────────────────────────

    /**
     * Opens a conversation about a live listing.
     *
     * <p>Live only: a buyer can only ask about something the marketplace is currently showing them, which is
     * the same rule the shortlist applies and for the same reason.
     */
    @Transactional
    public EnquiryResponse raise(RaiseEnquiryRequest request) {
        Long userId = AuthContext.requireUserId();
        User buyer = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Property property = liveProperty(request.propertyReference());

        EnquiryTicket ticket = repository.save(EnquiryTicket.builder()
                .reference(nextReference())
                .tenantId(property.getTenantId())
                .tenantName(property.getTenantName())
                .propertyId(property.getId())
                .propertyReference(property.getReference())
                .propertyTitle(property.getTitle())
                .userId(userId)
                .buyerName(buyer.fullName())
                .buyerEmail(blankTo(request.contactEmail(), buyer.getEmail()))
                .buyerPhone(blankTo(request.contactPhone(), buyer.getPhone()))
                .subject(blankToNull(request.subject()))
                .createdBy(buyer.getUsername())
                .updatedBy(buyer.getUsername())
                .build());

        /*
         * Onto a desk, if a rule says whose (M12).
         *
         * Before this, an enquiry arrived unassigned and attached itself to whoever opened it first — which
         * means the ones nobody opened were the ones nobody was accountable for. Routing never fails the
         * enquiry: no rule, or a rule pointing at somebody who has left, leaves it unassigned exactly as
         * before.
         */
        assignment.routeFor(OperationsConstants.WORK_ENQUIRY, property.getTenantId(),
                        property.getCounty(), property.getPropertyType())
                .ifPresent(route -> {
                    ticket.setAssignedToUserId(route.userId());
                    ticket.setAssignedToName(route.userName());
                    repository.save(ticket);
                    log.debug("Enquiry {} routed to {} by rule {}", ticket.getReference(),
                            route.userName(), route.ruleReference());
                });

        addMessage(ticket, AppConstant.SIDE_BUYER, userId, buyer.fullName(), request.message());

        audit.record(AppConstant.AUDIT_ENQUIRY_RAISED, "EnquiryTicket", ticket.getId(), null,
                ticket.getReference() + " on " + property.getReference());
        notifier.toSeller(property.getTenantId(),
                "New enquiry about " + property.getTitle(),
                buyer.fullName() + " has asked a question about " + property.getTitle() + ".",
                "/app/enquiries?ref=" + ticket.getReference());

        return toResponse(ticket, true);
    }

    @Transactional(readOnly = true)
    public PagedResponse<EnquiryResponse> mine(EnquiryListRequest request) {
        var page = repository.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return withLastMessage(page);
    }

    @Transactional(readOnly = true)
    public EnquiryResponse mineByReference(String reference) {
        EnquiryTicket ticket = repository
                .findMineByReference(trim(reference), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Enquiry", reference));
        return toResponse(ticket, true);
    }

    @Transactional(readOnly = true)
    public long myCount() {
        return repository.countByUserId(AuthContext.requireUserId());
    }

    /** The buyer's own follow-up on their own conversation. */
    @Transactional
    public EnquiryResponse addBuyerMessage(String reference, ReplyRequest request) {
        Long userId = AuthContext.requireUserId();
        EnquiryTicket ticket = repository.findMineByReference(trim(reference), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Enquiry", reference));
        if (ticket.isClosed()) {
            throw new HodiException("That conversation has been closed. Start a new enquiry.",
                    HttpStatus.CONFLICT);
        }

        User buyer = users.findById(userId).orElseThrow();
        addMessage(ticket, AppConstant.SIDE_BUYER, userId, buyer.fullName(), request.message());

        notifier.toSeller(ticket.getTenantId(),
                "New message about " + ticket.getPropertyTitle(),
                buyer.fullName() + " has added a message to enquiry " + ticket.getReference() + ".",
                "/app/enquiries?ref=" + ticket.getReference());
        return toResponse(ticket, true);
    }

    // ── the seller's side ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<EnquiryResponse> list(EnquiryListRequest request) {
        Specification<EnquiryTicket> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("propertyReference", blankToNull(request.getPropertyReference())),
                awaitingIs(request.getAwaiting()),
                TenantScope.restrict("tenantId"));

        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return withLastMessage(page);
    }

    @Transactional(readOnly = true)
    public EnquiryResponse find(String reference) {
        return toResponse(loadForSeller(reference), true);
    }

    @Transactional
    public EnquiryResponse reply(String reference, ReplyRequest request) {
        EnquiryTicket ticket = loadForSeller(reference);
        if (ticket.isClosed()) {
            throw new HodiException("That conversation is closed. Reopening is not available.",
                    HttpStatus.CONFLICT);
        }

        UserPrincipal caller = AuthContext.require();
        addMessage(ticket, AppConstant.SIDE_SELLER, caller.getUserId(), caller.getFullName(),
                request.message());
        ticket.setState(AppConstant.ENQUIRY_ANSWERED);
        // Answering claims it: the commonest reason two people reply to the same enquiry is that neither
        // could tell the other had started.
        if (ticket.getAssignedToUserId() == null) {
            ticket.setAssignedToUserId(caller.getUserId());
            ticket.setAssignedToName(caller.getFullName());
        }
        repository.save(ticket);

        notifier.toBuyer(ticket.getUserId(),
                "A reply about " + ticket.getPropertyTitle(),
                ticket.getTenantName() + " has replied to your enquiry about " + ticket.getPropertyTitle()
                        + ".",
                "/account/conversations?tab=enquiries&ref=" + ticket.getReference());
        return toResponse(ticket, true);
    }

    /**
     * Names, or clears, the agent who brought this buyer — the earliest place it can be said, when the
     * bank's setting allows it this early. Carried onto the buyer's offer on this home, and from there onto
     * the booking.
     */
    @Transactional
    public EnquiryResponse setIntroducer(String reference, com.hodi.modules.bookings.BookingDtos.IntroducerRequest request) {
        introducers.assertAllowedAt(com.hodi.modules.agents.IntroducerService.FROM_ENQUIRY);
        EnquiryTicket ticket = loadForSeller(reference);
        Long agentId = introducers.resolve(request == null ? null : request.agentRef())
                .map(com.hodi.modules.agents.AgentProfile::getId).orElse(null);
        ticket.setIntroducedByAgentId(agentId);
        ticket.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(ticket), true);
    }

    @Transactional
    public EnquiryResponse assign(String reference, AssignRequest request) {
        EnquiryTicket ticket = loadForSeller(reference);
        if (request.userHashId() == null || request.userHashId().isBlank()) {
            ticket.setAssignedToUserId(null);
            ticket.setAssignedToName(null);
        } else {
            Long assigneeId = HashIdUtil.decodeId(request.userHashId());
            User assignee = users.findById(assigneeId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", request.userHashId()));
            ticket.setAssignedToUserId(assignee.getId());
            ticket.setAssignedToName(assignee.fullName());
        }
        ticket.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(ticket), true);
    }

    /**
     * File it.
     *
     * <p>Returns the thread, which it did not used to. The admin pane assigns this response straight over
     * the conversation it is showing, so a response without messages blanked the entire history at the
     * moment somebody closed it — the one action after which you are most likely to want to re-read what
     * was agreed. The same applies to {@link #assign}: handing a conversation to a colleague is not a
     * reason for it to disappear off the screen of the person handing it over.
     */
    @Transactional
    public EnquiryResponse close(String reference, CloseRequest request) {
        EnquiryTicket ticket = loadForSeller(reference);
        ticket.setState(AppConstant.ENQUIRY_CLOSED);
        ticket.setClosedAt(OffsetDateTime.now());
        ticket.setClosedByUserId(AuthContext.userId());
        ticket.setCloseReason(blankToNull(request == null ? null : request.reason()));
        ticket.setAwaitingSeller(false);
        ticket.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(ticket), true);
    }

    @Transactional(readOnly = true)
    public long awaitingForCaller() {
        Long tenantId = TenantScope.ownTenantId();
        return tenantId == null ? 0 : repository.countAwaiting(tenantId);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Adds a message and moves every aggregate that depends on it.
     *
     * <p>One method, so the count, the timestamp and "who owes a reply" cannot drift apart — three separate
     * writers is how an inbox ends up claiming a conversation is waiting on somebody who answered it.
     */
    private void addMessage(EnquiryTicket ticket, String side, Long authorId, String authorName,
                            String body) {
        OffsetDateTime now = OffsetDateTime.now();
        messages.save(EnquiryMessage.builder()
                .ticketId(ticket.getId())
                .authorSide(side)
                .authorUserId(authorId)
                .authorName(authorName)
                .body(body.trim())
                .createdAt(now)
                .build());

        ticket.setMessageCount(ticket.getMessageCount() + 1);
        ticket.setLastMessageAt(now);
        ticket.setLastMessageSide(side);
        ticket.setAwaitingSeller(AppConstant.SIDE_BUYER.equals(side));
        if (AppConstant.SIDE_BUYER.equals(side) && !ticket.isClosed()) {
            ticket.setState(AppConstant.ENQUIRY_OPEN);
        }
        repository.save(ticket);
    }

    /**
     * The seller's own, or the platform's.
     *
     * <p>{@code TenantScope.assertAllowed} rather than a query filter, because this is a single-row read by
     * a handle the caller already holds — and the assertion refuses with the reason rather than pretending
     * the row does not exist, which is the right answer for staff looking at their own organisation's data.
     */
    private EnquiryTicket loadForSeller(String reference) {
        EnquiryTicket ticket = repository.findByReference(trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Enquiry", reference));
        TenantScope.assertAllowed(ticket.getTenantId());
        return ticket;
    }

    private Property liveProperty(String reference) {
        return properties.findLiveByReference(trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));
    }

    private Specification<EnquiryTicket> awaitingIs(Boolean awaiting) {
        if (awaiting == null) return null;
        return (root, query, cb) -> cb.equal(root.get("awaitingSeller"), awaiting);
    }

    private EnquiryResponse toResponse(EnquiryTicket t, boolean withMessages) {
        List<MessageResponse> thread = withMessages
                ? messages.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                        .map(EnquiryService::asMessage)
                        .toList()
                : null;
        // On a single read the last message is already in the thread; taking it from there costs nothing
        // and keeps the two halves of the response describing the same conversation.
        MessageResponse last = thread == null || thread.isEmpty()
                ? null : thread.get(thread.size() - 1);
        return toResponse(t, thread, last);
    }

    private EnquiryResponse toResponse(EnquiryTicket t, List<MessageResponse> thread, MessageResponse last) {
        var introducer = introducers.byId(t.getIntroducedByAgentId());
        return new EnquiryResponse(
                t.getReference(), t.getPropertyReference(), t.getPropertyTitle(), t.getTenantName(),
                t.getBuyerName(), t.getBuyerEmail(), t.getBuyerPhone(), t.getSubject(), t.getState(),
                t.getAssignedToName(), t.getMessageCount(), t.getLastMessageAt(), t.getLastMessageSide(),
                t.isAwaitingSeller(), t.getCloseReason(), t.getCreatedAt(), thread, last,
                introducer.map(com.hodi.modules.agents.AgentProfile::getReference).orElse(null),
                introducer.map(com.hodi.modules.agents.AgentProfile::getFullName).orElse(null));
    }

    private static MessageResponse asMessage(EnquiryMessage m) {
        return new MessageResponse(m.getAuthorSide(), m.getAuthorName(), m.getBody(), m.getCreatedAt(), "MESSAGE", null);
    }

    /**
     * A page of tickets, each carrying its latest message, in two queries rather than one per row.
     *
     * <p>The repository returns every message for the page newest first, so the first one seen per ticket
     * is that ticket's latest — {@code merge} keeps it and discards the rest.
     */
    private PagedResponse<EnquiryResponse> withLastMessage(
            org.springframework.data.domain.Page<EnquiryTicket> page) {
        List<Long> ids = page.getContent().stream().map(EnquiryTicket::getId).toList();
        java.util.Map<Long, MessageResponse> latest = ids.isEmpty() ? java.util.Map.of()
                : messages.latestForTickets(ids).stream().collect(java.util.stream.Collectors.toMap(
                        EnquiryMessage::getTicketId, EnquiryService::asMessage, (first, older) -> first));
        return PagedResponse.from(page, t -> toResponse(t, null, latest.get(t.getId())));
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
