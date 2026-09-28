package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.leads.LeadDtos.*;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offers (M4, BRD FR045–FR048).
 *
 * <h2>The end of the funnel, and not a contract</h2>
 *
 * <p>Accepting an offer here tells a buyer a seller is willing to proceed. It transfers nothing, binds
 * nobody and does not take the listing off the marketplace — conveyancing is not on this platform, and a
 * screen that implied otherwise would be making a promise the software cannot keep. The listing's own
 * {@code SOLD} state is a separate act by the seller, when it is actually sold.
 *
 * <h2>One live offer at a time</h2>
 *
 * <p>A buyer with an outstanding offer who wants to change it is changing that one. A second row would give
 * the seller two figures from the same person with nothing saying which is current. The partial unique index
 * enforces it; the check here is what turns the refusal into a sentence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseRequestService {

    private static final String REFERENCE_PREFIX = "OF";

    private static final Set<String> FINANCING = Set.of(
            AppConstant.FINANCING_CASH, AppConstant.FINANCING_MORTGAGE,
            AppConstant.FINANCING_PART_EXCHANGE);

    private final PurchaseRequestRepository repository;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final AuditService audit;
    private final LeadNotifier notifier;
    private final LeadThreadService thread;
    private final BookingService bookings;
    private final UnitBookingRepository bookingRows;
    private final com.hodi.modules.agents.IntroducerService introducers;
    private final EnquiryTicketRepository enquiries;

    // ── the buyer's side ──────────────────────────────────────────────────────

    @Transactional
    public OfferResponse submit(SubmitOfferRequest request) {
        Long userId = AuthContext.requireUserId();
        User buyer = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Property property = properties.findLiveByReference(EnquiryService.trim(request.propertyReference()))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", request.propertyReference()));
        if (property.isUnitTypeListing()) {
            /*
             * An offer is for a home, not for a kind of home. A typology card stands for many units, and an
             * offer accepted against it could not become a booking: nobody could say which flat was sold.
             * The marketplace asks the buyer to pick one before this is reached; the guard is for anything
             * that does not.
             */
            throw new HodiException("Make the offer on a specific home — open one of this listing's units "
                    + "and offer there.", HttpStatus.CONFLICT);
        }

        repository.findLiveFor(userId, property.getId()).ifPresent(existing -> {
            throw new HodiException(
                    "You already have an offer of " + money(existing.getOfferAmount(),
                            existing.getCurrency()) + " outstanding on this property. Change or withdraw "
                            + "that one first.", HttpStatus.CONFLICT);
        });

        PurchaseRequest offer = repository.save(PurchaseRequest.builder()
                .reference(nextReference())
                .tenantId(property.getTenantId())
                .tenantName(property.getTenantName())
                .propertyId(property.getId())
                .propertyReference(property.getReference())
                .propertyTitle(property.getTitle())
                .askingPrice(property.getPrice())
                .userId(userId)
                .buyerName(buyer.fullName())
                .buyerEmail(buyer.getEmail())
                .buyerPhone(EnquiryService.blankTo(request.contactPhone(), buyer.getPhone()))
                .offerAmount(request.offerAmount())
                .originalAmount(request.offerAmount())
                .currency(property.getCurrency())
                .financing(financing(request.financing()))
                .affordabilityReference(EnquiryService.blankToNull(request.affordabilityReference()))
                .productReference(EnquiryService.blankToNull(request.productReference()))
                .depositAvailable(request.depositAvailable())
                .buyerMessage(EnquiryService.blankToNull(request.message()))
                // Carried from this buyer's enquiry on this home, when one named who brought them.
                .introducedByAgentId(enquiries.findIntroducedFor(userId, property.getId()).stream()
                        .findFirst().map(EnquiryTicket::getIntroducedByAgentId).orElse(null))
                .createdBy(buyer.getUsername())
                .updatedBy(buyer.getUsername())
                .build());

        thread.recordAsBuyer(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), userId, buyer.fullName(),
                EnquiryService.blankTo(request.message(),
                        "Offered " + money(request.offerAmount(), property.getCurrency()) + "."),
                AppConstant.PURCHASE_SUBMITTED, "OFFER", request.offerAmount());

        audit.record(AppConstant.AUDIT_OFFER_SUBMITTED, "PurchaseRequest", offer.getId(), null,
                offer.getReference() + " on " + property.getReference());
        notifier.toSeller(property.getTenantId(),
                "Offer on " + property.getTitle(),
                buyer.fullName() + " has offered " + money(request.offerAmount(), property.getCurrency())
                        + " for " + property.getTitle() + " (asking "
                        + money(property.getPrice(), property.getCurrency()) + ").",
                "/app/offers?ref=" + offer.getReference());

        return toResponse(offer);
    }

    @Transactional(readOnly = true)
    public PagedResponse<OfferResponse> mine(OfferListRequest request) {
        var page = repository.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return withThreads(page);
    }

    @Transactional(readOnly = true)
    public long myCount() {
        return repository.countByUserId(AuthContext.requireUserId());
    }

    @Transactional
    public OfferResponse withdraw(String reference) {
        PurchaseRequest offer = repository
                .findMineByReference(EnquiryService.trim(reference), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Offer", reference));
        if (!offer.isLive()) {
            throw new HodiException("That offer has already been settled.", HttpStatus.CONFLICT);
        }

        offer.setState(AppConstant.PURCHASE_WITHDRAWN);
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);

        thread.recordAsBuyer(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), offer.getUserId(),
                offer.getBuyerName(), "Withdrew the offer.", AppConstant.PURCHASE_WITHDRAWN, "WITHDRAWN", null);

        notifier.toSeller(offer.getTenantId(),
                "Offer withdrawn: " + offer.getPropertyTitle(),
                offer.getBuyerName() + " has withdrawn their offer on " + offer.getPropertyTitle() + ".",
                "/app/offers?ref=" + offer.getReference());
        return toResponse(offer);
    }

    // ── the seller's side ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<OfferResponse> list(OfferListRequest request) {
        Specification<PurchaseRequest> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", EnquiryService.blankToNull(request.getState())),
                SearchSpecs.eq("propertyReference",
                        EnquiryService.blankToNull(request.getPropertyReference())),
                TenantScope.restrict("tenantId"));

        return withThreads(
                repository.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    /**
     * A word from the seller on a live offer.
     *
     * <p>An offer is a conversation until it is settled: a counter, a question about the deposit, a date.
     * Once accepted, declined or withdrawn the thread closes — the decision was the last word, and what
     * follows an acceptance happens on the booking. Same thread the decisions write to, so the whole
     * exchange reads in order.
     */
    @Transactional
    public OfferResponse message(String reference, ReplyRequest request) {
        PurchaseRequest offer = loadForSeller(reference);
        if (!offer.isLive()) {
            throw new HodiException("This offer is " + offer.getState().toLowerCase(java.util.Locale.ROOT)
                    + "; the conversation is closed.", HttpStatus.CONFLICT);
        }
        thread.record(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), request.message(), offer.getState());
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);
        notifier.toBuyer(offer.getUserId(), "About your offer on " + offer.getPropertyTitle(),
                offer.getTenantName() + " has replied about your offer on " + offer.getPropertyTitle() + ".",
                "/account/conversations?tab=offers&ref=" + offer.getReference());
        return toResponse(offer);
    }

    /**
     * The seller comes back with a figure.
     *
     * <p>A counter is the seller considering the offer, so it also marks it as being considered. It stands
     * until the buyer accepts it — which makes it their offer — or counters back. Only one counter stands at
     * a time; a new one replaces it.
     */
    @Transactional
    public OfferResponse counter(String reference, CounterRequest request) {
        PurchaseRequest offer = loadForSeller(reference);
        if (!offer.isLive()) {
            throw new HodiException("This offer is " + offer.getState().toLowerCase(java.util.Locale.ROOT)
                    + "; the conversation is closed.", HttpStatus.CONFLICT);
        }
        offer.setCounterAmount(request.amount());
        offer.setCounterBy(AppConstant.SIDE_SELLER);
        offer.setState(AppConstant.PURCHASE_UNDER_REVIEW);
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);
        String line = "Countered at " + money(request.amount(), offer.getCurrency()) + ".";
        thread.record(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(),
                EnquiryService.blankTo(request.note(), line), offer.getState(), "COUNTER", request.amount());
        notifier.toBuyer(offer.getUserId(), "A counter on your offer for " + offer.getPropertyTitle(),
                offer.getTenantName() + " has come back at " + money(request.amount(), offer.getCurrency())
                        + " on " + offer.getPropertyTitle() + ". Accept it or counter from your offers.",
                "/account/conversations?tab=offers&ref=" + offer.getReference());
        return toResponse(offer);
    }

    /** The buyer takes the seller's counter: it becomes their offer, for the seller to accept. */
    @Transactional
    public OfferResponse acceptCounter(String reference) {
        PurchaseRequest offer = loadMine(reference);
        if (offer.getCounterAmount() == null) {
            throw new HodiException("There is no counter on this offer to accept.", HttpStatus.CONFLICT);
        }
        BigDecimal figure = offer.getCounterAmount();
        offer.setOfferAmount(figure);
        offer.setCounterAmount(null);
        offer.setCounterBy(null);
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);
        thread.recordAsBuyer(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), offer.getUserId(), offer.getBuyerName(),
                "Accepted the counter of " + money(figure, offer.getCurrency()) + ".", offer.getState(),
                "ACCEPTED_COUNTER", figure);
        notifier.toSeller(offer.getTenantId(), "Counter accepted: " + offer.getPropertyTitle(),
                offer.getBuyerName() + " has accepted your counter of " + money(figure, offer.getCurrency())
                        + " on " + offer.getPropertyTitle() + ". Accept the offer to proceed.",
                "/app/offers/" + offer.getReference());
        return toResponse(offer);
    }

    /** The buyer comes back with a figure of their own; any counter standing is answered by it. */
    @Transactional
    public OfferResponse buyerCounter(String reference, CounterRequest request) {
        PurchaseRequest offer = loadMine(reference);
        offer.setOfferAmount(request.amount());
        offer.setCounterAmount(null);
        offer.setCounterBy(null);
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);
        String line = "Offered " + money(request.amount(), offer.getCurrency()) + " instead.";
        thread.recordAsBuyer(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), offer.getUserId(), offer.getBuyerName(),
                EnquiryService.blankTo(request.note(), line), offer.getState(), "COUNTER", request.amount());
        notifier.toSeller(offer.getTenantId(), "A new figure on an offer: " + offer.getPropertyTitle(),
                offer.getBuyerName() + " now offers " + money(request.amount(), offer.getCurrency()) + " for "
                        + offer.getPropertyTitle() + ".",
                "/app/offers/" + offer.getReference());
        return toResponse(offer);
    }

    /** The buyer's own live offer, or a refusal that says why. */
    private PurchaseRequest loadMine(String reference) {
        PurchaseRequest offer = repository.findMineByReference(EnquiryService.trim(reference), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Offer", reference));
        if (!offer.isLive()) {
            throw new HodiException("This offer is " + offer.getState().toLowerCase(java.util.Locale.ROOT)
                    + "; the conversation is closed.", HttpStatus.CONFLICT);
        }
        return offer;
    }

    /** A word from the buyer on their own live offer. */
    @Transactional
    public OfferResponse addBuyerMessage(String reference, ReplyRequest request) {
        Long userId = AuthContext.requireUserId();
        PurchaseRequest offer = repository.findMineByReference(EnquiryService.trim(reference), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer", reference));
        if (!offer.isLive()) {
            throw new HodiException("This offer is " + offer.getState().toLowerCase(java.util.Locale.ROOT)
                    + "; the conversation is closed.", HttpStatus.CONFLICT);
        }
        thread.recordAsBuyer(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), userId, offer.getBuyerName(),
                request.message(), offer.getState());
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);
        notifier.toSeller(offer.getTenantId(), "New message on an offer: " + offer.getPropertyTitle(),
                offer.getBuyerName() + " has added a message to their offer on " + offer.getPropertyTitle() + ".",
                "/app/offers/" + offer.getReference());
        return toResponse(offer);
    }

    /** One offer, with everything said about it, for the seller's detail page. */
    @Transactional(readOnly = true)
    public OfferResponse find(String reference) {
        return toResponse(loadForSeller(reference));
    }

    /**
     * Accept, decline, or mark as being looked at.
     *
     * <p>{@code REVIEW} exists because a seller who has seen an offer and not yet answered it is in a state
     * the buyer deserves to be told about — "submitted" for a week reads as ignored, and the commonest
     * complaint about offer funnels is silence rather than refusal.
     */
    @Transactional
    public OfferResponse decide(String reference, DecideOfferRequest request) {
        PurchaseRequest offer = loadForSeller(reference);
        if (!offer.isLive()) {
            throw new HodiException("That offer has already been settled.", HttpStatus.CONFLICT);
        }

        String decision = EnquiryService.trim(request.decision()).toUpperCase();
        String line;
        String kind;
        BigDecimal figure = null;
        switch (decision) {
            case "REVIEW" -> {
                offer.setState(AppConstant.PURCHASE_UNDER_REVIEW);
                kind = "REVIEW";
                line = offer.getTenantName() + " is considering your offer on " + offer.getPropertyTitle()
                        + ".";
            }
            case "ACCEPT" -> {
                offer.setState(AppConstant.PURCHASE_ACCEPTED);
                offer.setDecidedAt(OffsetDateTime.now());
                // The figure agreed is the buyer's current offer: a counter still standing is not agreed.
                offer.setAgreedAmount(offer.getOfferAmount());
                offer.setCounterAmount(null);
                offer.setCounterBy(null);
                kind = "ACCEPTED";
                figure = offer.getOfferAmount();
                line = offer.getTenantName() + " has accepted your offer of "
                        + money(offer.getOfferAmount(), offer.getCurrency()) + " for "
                        + offer.getPropertyTitle() + ". They will be in touch about what happens next.";
            }
            case "DECLINE" -> {
                offer.setState(AppConstant.PURCHASE_DECLINED);
                offer.setDecidedAt(OffsetDateTime.now());
                kind = "DECLINED";
                line = offer.getTenantName() + " has declined your offer on " + offer.getPropertyTitle()
                        + ".";
            }
            default -> throw new HodiException("Say whether you are accepting, declining or reviewing.",
                    HttpStatus.BAD_REQUEST);
        }

        offer.setDecisionNote(EnquiryService.blankToNull(request.note()));
        if (offer.getDecidedAt() != null) offer.setDecidedByUserId(AuthContext.userId());
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);

        /*
         * Appended, not replaced. `decisionNote` still holds the latest word — the notification line and
         * the seller's table both read it — but an offer countered twice used to retain only the second
         * note, which is what "the conversation was not saved" meant.
         */
        thread.record(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(),
                EnquiryService.blankTo(request.note(), line), offer.getState(), kind, figure);

        audit.record(AppConstant.AUDIT_OFFER_DECIDED, "PurchaseRequest", offer.getId(), null,
                offer.getReference() + " " + offer.getState());
        notifier.toBuyer(offer.getUserId(), "About your offer on " + offer.getPropertyTitle(), line,
                "/account/conversations?tab=offers&ref=" + offer.getReference());
        return toResponse(offer);
    }

    /**
     * Turns an accepted offer into a booking.
     *
     * <p>This is what "accepted" was missing: the reservation of the home and the account the money lands
     * in. The booking is made through the bookings module's own front door, so every rule about who may book
     * what, and whether the home is still free, is the one rule; this method only supplies what the offer
     * already knows — the buyer, the home, the figure — and writes the result back onto the offer, once.
     *
     * <p>The buyer's account is linked to the booking. That is the point of converting rather than
     * retyping: the booking appears under their own bookings, and they can pay it from there.
     */
    @Transactional
    public OfferResponse book(String reference, BookFromOfferRequest request) {
        PurchaseRequest offer = loadForSeller(reference);
        if (!AppConstant.PURCHASE_ACCEPTED.equals(offer.getState())) {
            throw new HodiException("Only an accepted offer can become a booking.", HttpStatus.CONFLICT);
        }
        if (offer.getBookingId() != null) {
            String existing = bookingRows.findById(offer.getBookingId()).map(UnitBooking::getReference).orElse("a booking");
            throw new HodiException("This offer already became " + existing + ".", HttpStatus.CONFLICT);
        }
        Property home = properties.findById(offer.getPropertyId())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", offer.getPropertyReference()));
        if (offer.getBuyerPhone() == null || offer.getBuyerPhone().isBlank()) {
            throw new HodiException("The offer carries no phone number for the buyer, and a booking needs one — "
                    + "payments are matched against it. Book the home from its own page instead.", HttpStatus.CONFLICT);
        }

        BookFromOfferRequest terms = request == null ? new BookFromOfferRequest(null, null, null, null, null, null) : request;
        CreateBookingRequest asBooking = new CreateBookingRequest(null,
                offer.getBuyerName() == null || offer.getBuyerName().isBlank() ? "Buyer" : offer.getBuyerName(),
                offer.getBuyerPhone(), offer.getBuyerEmail(), null,
                terms.priceAgreed() != null ? terms.priceAgreed() : offer.getOfferAmount(),
                terms.depositDue() != null ? terms.depositDue() : offer.getDepositAvailable(),
                terms.paymentPlan(), terms.holdDays(),
                EnquiryService.blankTo(terms.notes(), "From offer " + offer.getReference()
                        + (offer.getBuyerMessage() == null ? "" : " — \"" + offer.getBuyerMessage() + "\"")),
                terms.instalments(),
                // Who brought the buyer travels with them onto the booking.
                introducers.byId(offer.getIntroducedByAgentId())
                        .map(com.hodi.modules.agents.AgentProfile::getReference).orElse(null));
        BookingResponse booked = bookings.createForProperty(HashIdUtil.encodeId(home.getId()), asBooking);

        UnitBooking booking = bookingRows.findById(HashIdUtil.decodeId(booked.id())).orElseThrow();
        booking.setBuyerUserId(offer.getUserId());
        booking.setUpdatedBy(AuthContext.username());
        bookingRows.save(booking);

        offer.setBookingId(booking.getId());
        offer.setUpdatedBy(AuthContext.username());
        repository.save(offer);

        String line = offer.getTenantName() + " has booked " + offer.getPropertyTitle() + " for you as "
                + booking.getReference() + ". You can now pay towards it from your bookings.";
        thread.record(AppConstant.LEAD_PURCHASE_REQUEST, offer.getId(), line, offer.getState());
        audit.record(AppConstant.AUDIT_OFFER_DECIDED, "PurchaseRequest", offer.getId(), null,
                offer.getReference() + " booked as " + booking.getReference());
        notifier.toBuyer(offer.getUserId(), "Your offer on " + offer.getPropertyTitle() + " is now a booking",
                line, "/account/bookings");
        log.info("Offer {} converted to booking {} by {}", offer.getReference(), booking.getReference(),
                AuthContext.username());
        return toResponse(offer);
    }

    @Transactional(readOnly = true)
    public long liveForCaller() {
        Long tenantId = TenantScope.ownTenantId();
        return tenantId == null ? 0 : repository.countLive(tenantId);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Names, or clears, the agent who brought this buyer. Until the offer becomes a booking: after that the
     * booking is the sale, and is where the name lives.
     */
    @Transactional
    public OfferResponse setIntroducer(String reference, com.hodi.modules.bookings.BookingDtos.IntroducerRequest request) {
        introducers.assertAllowedAt(com.hodi.modules.agents.IntroducerService.FROM_OFFER);
        PurchaseRequest offer = loadForSeller(reference);
        if (offer.getBookingId() != null) {
            throw new HodiException("This offer is already a booking; name who brought the buyer on the booking.",
                    HttpStatus.CONFLICT);
        }
        Long agentId = introducers.resolve(request == null ? null : request.agentRef())
                .map(com.hodi.modules.agents.AgentProfile::getId).orElse(null);
        offer.setIntroducedByAgentId(agentId);
        offer.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(offer));
    }

    private PurchaseRequest loadForSeller(String reference) {
        PurchaseRequest offer = repository.findByReference(EnquiryService.trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Offer", reference));
        TenantScope.assertAllowed(offer.getTenantId());
        return offer;
    }

    private static String financing(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase();
        return FINANCING.contains(value) ? value : AppConstant.FINANCING_MORTGAGE;
    }

    private static String money(BigDecimal amount, String currency) {
        if (amount == null) return "";
        return (currency == null ? "KES" : currency) + " "
                + NumberFormat.getIntegerInstance(Locale.UK).format(amount);
    }

    /** A page of offers with every thread fetched once. See the note on the viewings equivalent. */
    private PagedResponse<OfferResponse> withThreads(
            org.springframework.data.domain.Page<PurchaseRequest> page) {
        var byLead = thread.threads(AppConstant.LEAD_PURCHASE_REQUEST,
                page.getContent().stream().map(PurchaseRequest::getId).toList());
        return PagedResponse.from(page, p -> toResponse(p, byLead.getOrDefault(p.getId(), List.of())));
    }

    private OfferResponse toResponse(PurchaseRequest p) {
        return toResponse(p, thread.thread(AppConstant.LEAD_PURCHASE_REQUEST, p.getId()));
    }

    private OfferResponse toResponse(PurchaseRequest p, List<MessageResponse> messages) {
        // Only an accepted-and-converted offer has one, so the lookup runs for those rows alone.
        String bookingReference = p.getBookingId() == null ? null
                : bookingRows.findById(p.getBookingId()).map(UnitBooking::getReference).orElse(null);
        var introducer = introducers.byId(p.getIntroducedByAgentId());
        return new OfferResponse(
                p.getReference(), p.getPropertyReference(), p.getPropertyTitle(), p.getTenantName(),
                p.getAskingPrice(), p.getBuyerName(), p.getBuyerEmail(), p.getBuyerPhone(),
                p.getOfferAmount(), p.getCurrency(), p.getFinancing(), p.getAffordabilityReference(),
                p.getProductReference(), p.getDepositAvailable(), p.getBuyerMessage(), p.getState(),
                p.getDecisionNote(), p.getDecidedAt(), p.getCreatedAt(), messages,
                HashIdUtil.encodeId(p.getBookingId()), bookingReference,
                p.getOriginalAmount() == null ? p.getOfferAmount() : p.getOriginalAmount(),
                p.getCounterAmount(), p.getCounterBy(), p.getAgreedAmount(),
                introducer.map(com.hodi.modules.agents.AgentProfile::getReference).orElse(null),
                introducer.map(com.hodi.modules.agents.AgentProfile::getFullName).orElse(null));
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
