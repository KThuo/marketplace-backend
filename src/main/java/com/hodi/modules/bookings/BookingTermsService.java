package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.BookingPolicyService.BookingPolicy;
import com.hodi.modules.developments.Development;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.kyc.VaultDocument;
import com.hodi.modules.kyc.VaultDocumentRepository;
import com.hodi.modules.properties.Property;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The terms a booking is made under, and the buyer's answer to them (lapsed-bookings plan §2.5).
 *
 * <h2>One page, filled</h2>
 *
 * <p>A versioned template at the platform, with placeholders; the same page renders it for a booking
 * (from the figures kept on the booking's terms row) and for a listing nobody has booked (from the
 * listing and its development's policy today). Nothing is copied: what a booking keeps is the version
 * and the figures.
 *
 * <h2>Two ways to say yes</h2>
 *
 * <p>The buyer, signed in, on their own booking — the one that counts whenever they have an account. Or
 * the sales office, for a buyer without one, with the signed form in the vault — required, not a
 * checkbox. A buyer who later signs in sees what was signed and may confirm it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingTermsService {

    public static final String TERMS_NONE = "NONE";
    public static final String TERMS_PRESENTED = "PRESENTED";
    public static final String TERMS_ACCEPTED = "ACCEPTED";
    public static final String TERMS_DECLINED = "DECLINED";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final BookingTermsRepository terms;
    private final BookingTermsTemplateRepository templates;
    private final BookingPolicyService policies;
    private final UnitBookingRepository bookings;
    private final DocumentService documents;
    private final VaultDocumentRepository vaultDocuments;
    private final BookingNotifier notifier;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** The terms as a page: the rendered text, the version and figures behind it, and where the answer stands. */
    public record TermsView(
            int templateVersion,
            String body,
            Map<String, Object> figures,
            String state,
            OffsetDateTime presentedAt,
            OffsetDateTime acceptedAt,
            OffsetDateTime declinedAt,
            String declineReason,
            String channel,
            String decidedByName,
            OffsetDateTime confirmedAt,
            /** The signed form, when accepted on paper. */
            String documentReference,
            String documentName,
            /** What the caller may do now, decided here so the screen and the service agree. */
            boolean mayAccept,
            boolean mayDecline,
            boolean mayConfirm,
            boolean mayRecordOnPaper) {}

    public record DeclineRequest(@NotBlank(message = "Say why — it stays on the record") String reason) {}

    public record TemplateResponse(int version, String body, String note, OffsetDateTime createdAt, String createdBy,
                                   List<String> placeholders) {}

    public record NewTemplateRequest(@NotBlank(message = "The terms cannot be empty") String body, String note) {}

    // ── the template ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TemplateResponse activeTemplate() {
        return toResponse(active());
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> templateHistory() {
        return templates.findAllByOrderByVersionDesc().stream().map(this::toResponse).toList();
    }

    /** A new version. The old one stays, because bookings point at it. */
    @Transactional
    public TemplateResponse newTemplate(NewTemplateRequest request) {
        BookingTermsTemplate current = active();
        BookingTermsTemplate next = templates.save(BookingTermsTemplate.builder()
                .version(current.getVersion() + 1)
                .body(request.body().trim())
                .note(request.note() == null || request.note().isBlank() ? null : request.note().trim())
                .createdBy(AuthContext.username())
                .build());
        audit.record(AppConstant.ACTION_UPDATE, "BookingTermsTemplate", next.getId(),
                "version " + current.getVersion(), "version " + next.getVersion());
        return toResponse(next);
    }

    private BookingTermsTemplate active() {
        return templates.findTopByOrderByVersionDesc()
                .orElseThrow(() -> new HodiException("No booking terms exist — the migration has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    // ── presenting ────────────────────────────────────────────────────────────

    /**
     * Fills the active template for a fresh booking and puts it in front of the buyer.
     *
     * <p>Called in the transaction that creates the booking; the booking goes to PRESENTED and the buyer is
     * told. A completed sale recorded by hand is not presented: there is nothing left to agree to.
     */
    @Transactional
    public BookingTerms present(UnitBooking booking, Property home, Development development) {
        BookingTermsTemplate template = active();
        BookingPolicy policy = policies.policyFor(development);
        Map<String, Object> figures = figuresFor(booking, home, development, policy);

        terms.findCurrent(booking.getId()).ifPresent(old -> {
            old.setSupersededAt(OffsetDateTime.now());
            terms.save(old);
        });
        BookingTerms row = terms.save(BookingTerms.builder()
                .bookingId(booking.getId())
                .templateVersion(template.getVersion())
                .figures(figures)
                .createdBy(AuthContext.current().map(UserPrincipal::getUsername).orElse(AppConstant.USERNAME_SYSTEM))
                .build());
        booking.setTermsState(TERMS_PRESENTED);
        bookings.save(booking);

        notifier.toBuyer(booking, "BOOKING_TERMS_PRESENTED",
                Map.of("home", String.valueOf(figures.get("home")), "reference", booking.getReference()));
        return row;
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /** The terms on a booking, rendered, with what this caller may do. */
    @Transactional(readOnly = true)
    public TermsView view(UnitBooking booking, boolean callerIsBuyer, boolean callerMayManage) {
        Optional<BookingTerms> current = terms.findCurrent(booking.getId());
        if (current.isEmpty()) {
            // Before terms existed. Say so rather than invent an agreement.
            return new TermsView(0, "This booking was made before booking terms were introduced; none were "
                    + "presented for it.", Map.of(), TERMS_NONE, null, null, null, null, null, null, null,
                    null, null, false, false, false, false);
        }
        BookingTerms row = current.get();
        BookingTermsTemplate template = templates.findByVersion(row.getTemplateVersion()).orElseGet(this::active);
        String body = render(template.getBody(), row.getFigures());
        VaultDocument document = row.getDocumentId() == null ? null
                : vaultDocuments.findById(row.getDocumentId()).orElse(null);
        boolean open = !row.isDecided() && booking.isLive();
        boolean acceptedOnPaper = row.getAcceptedAt() != null && BookingTerms.CHANNEL_PAPER.equals(row.getChannel());
        return new TermsView(row.getTemplateVersion(), body, row.getFigures(), booking.getTermsState(),
                row.getPresentedAt(), row.getAcceptedAt(), row.getDeclinedAt(), row.getDeclineReason(),
                row.getChannel(), row.getDecidedByName(), row.getConfirmedAt(),
                document == null ? null : document.getReference(),
                document == null ? null : document.getOriginalName(),
                callerIsBuyer && open, callerIsBuyer && open,
                callerIsBuyer && acceptedOnPaper && row.getConfirmedAt() == null,
                callerMayManage && open);
    }

    /** The terms a listing would be booked under today: the active template, filled from the listing. */
    @Transactional(readOnly = true)
    public TermsView viewForListing(Property home, Development development) {
        BookingTermsTemplate template = active();
        BookingPolicy policy = policies.policyFor(development);
        Map<String, Object> figures = figuresFor(null, home, development, policy);
        return new TermsView(template.getVersion(), render(template.getBody(), figures), figures, TERMS_NONE,
                null, null, null, null, null, null, null, null, null, false, false, false, false);
    }

    // ── the buyer's answer ────────────────────────────────────────────────────

    /** The buyer, on their own booking. */
    @Transactional
    public void accept(UnitBooking booking) {
        BookingTerms row = requireOpen(booking);
        UserPrincipal caller = AuthContext.require();
        row.setAcceptedAt(OffsetDateTime.now());
        row.setChannel(BookingTerms.CHANNEL_PORTAL);
        row.setDecidedByUserId(caller.getUserId());
        row.setDecidedByName(caller.getFullName());
        terms.save(row);
        booking.setTermsState(TERMS_ACCEPTED);
        booking.setUpdatedBy(caller.getUsername());
        bookings.save(booking);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", booking.getId(), "terms PRESENTED",
                "terms ACCEPTED by the buyer (version " + row.getTemplateVersion() + ")");
        log.info("Booking {} terms accepted by the buyer", booking.getReference());
    }

    /**
     * The buyer says no. Marks the row; the caller closes the booking, because a declined hold is a
     * cancelled one and that is the booking service's verb.
     */
    @Transactional
    public BookingTerms markDeclined(UnitBooking booking, String reason) {
        BookingTerms row = requireOpen(booking);
        UserPrincipal caller = AuthContext.require();
        row.setDeclinedAt(OffsetDateTime.now());
        row.setDeclineReason(reason);
        row.setChannel(BookingTerms.CHANNEL_PORTAL);
        row.setDecidedByUserId(caller.getUserId());
        row.setDecidedByName(caller.getFullName());
        terms.save(row);
        booking.setTermsState(TERMS_DECLINED);
        return row;
    }

    /**
     * The sales office, for a buyer without an account: the signed form is the acceptance, and it goes
     * into the vault against the booking. Required — a checkbox is not a signature.
     */
    @Transactional
    public void acceptOnPaper(UnitBooking booking, Property home, MultipartFile signed) {
        BookingTerms row = requireOpen(booking);
        if (signed == null || signed.isEmpty()) {
            throw new HodiException("Attach the terms the buyer signed.", HttpStatus.BAD_REQUEST);
        }
        UserPrincipal caller = AuthContext.require();
        VaultDocument stored = documents.store(signed, "bookings", "BOOKING_TERMS_SIGNED",
                "Signed booking terms " + booking.getReference(), booking.getTenantId(), booking.getBuyerUserId(),
                LocalDate.now(), null, "BOOKINGS_VIEW");
        row.setAcceptedAt(OffsetDateTime.now());
        row.setChannel(BookingTerms.CHANNEL_PAPER);
        row.setDecidedByUserId(caller.getUserId());
        row.setDecidedByName(caller.getFullName());
        row.setDocumentId(stored.getId());
        terms.save(row);
        booking.setTermsState(TERMS_ACCEPTED);
        booking.setUpdatedBy(caller.getUsername());
        bookings.save(booking);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", booking.getId(), "terms PRESENTED",
                "terms ACCEPTED on paper, recorded by " + caller.getUsername() + ", form " + stored.getReference());
        log.info("Booking {} terms accepted on paper, form {}", booking.getReference(), stored.getReference());
    }

    /** A buyer confirming in the portal what they signed on paper. Welcome, not required. */
    @Transactional
    public void confirm(UnitBooking booking) {
        BookingTerms row = terms.findCurrent(booking.getId())
                .filter(t -> t.getAcceptedAt() != null && BookingTerms.CHANNEL_PAPER.equals(t.getChannel()))
                .orElseThrow(() -> new HodiException("There is nothing on paper to confirm.", HttpStatus.CONFLICT));
        if (row.getConfirmedAt() != null) return;
        row.setConfirmedAt(OffsetDateTime.now());
        terms.save(row);
        audit.record(AppConstant.ACTION_UPDATE, "UnitBooking", booking.getId(), null,
                "terms signed on paper confirmed by the buyer in the portal");
    }

    /** The signed form's bytes, for anyone who may read the booking. */
    @Transactional
    public DocumentService.Fetched signedForm(UnitBooking booking) {
        BookingTerms row = terms.findCurrent(booking.getId())
                .filter(t -> t.getDocumentId() != null)
                .orElseThrow(() -> new com.hodi.common.exception.ResourceNotFoundException("Signed terms", booking.getReference()));
        VaultDocument document = vaultDocuments.findById(row.getDocumentId())
                .orElseThrow(() -> new com.hodi.common.exception.ResourceNotFoundException("Signed terms", booking.getReference()));
        return documents.readTrusted(document, "booking " + booking.getReference() + " terms");
    }

    /**
     * Whether money may be taken against this booking's terms. Legacy bookings (NONE) may; a presented or
     * declined one may not, and the sentence says what to do about it.
     */
    public static void assertMayPay(UnitBooking booking, boolean byStaff) {
        switch (booking.getTermsState() == null ? TERMS_NONE : booking.getTermsState()) {
            case TERMS_PRESENTED -> throw new HodiException(byStaff
                    ? "The buyer has not accepted the booking terms yet. Record the signed terms first, or ask "
                            + "them to accept in their portal."
                    : "Read and accept the terms of your booking before paying.", HttpStatus.CONFLICT);
            case TERMS_DECLINED -> throw new HodiException("The buyer declined the terms of this booking.",
                    HttpStatus.CONFLICT);
            default -> { }
        }
    }

    // ── figures and rendering ─────────────────────────────────────────────────

    private Map<String, Object> figuresFor(UnitBooking booking, Property home, Development development,
                                           BookingPolicy policy) {
        String currency = booking != null ? booking.getCurrency()
                : home.getCurrency() == null ? "KES" : home.getCurrency();
        BigDecimal price = booking != null ? booking.getPriceAgreed() : home.getPrice();
        BigDecimal deposit = booking != null ? booking.getDepositDue() : null;
        String plan = booking != null ? booking.getPaymentPlan() : AppConstant.PLAN_INSTALMENTS;
        int holdDays = booking != null && booking.getExpiresAt() != null && booking.getBookedOn() != null
                ? (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(booking.getBookedOn(),
                        booking.getExpiresAt().atZoneSameInstant(NAIROBI).toLocalDate()))
                : 14;
        String homeLabel = home.getUnitLabel() != null && development != null
                ? home.getUnitLabel() + ", " + development.getName()
                : home.getTitle() != null ? home.getTitle() : home.getReference();
        String seller = development != null && development.getTenantName() != null ? development.getTenantName()
                : home.getTenantName() != null ? home.getTenantName() : "the seller";

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("home", homeLabel);
        f.put("seller", seller);
        f.put("buyer", booking != null ? booking.getBuyerName() : "you");
        f.put("currency", currency);
        f.put("price", price == null ? "the listed price" : money(price, currency));
        f.put("deposit", deposit == null ? "what the seller asks to hold it" : money(deposit, currency));
        f.put("plan", AppConstant.PLAN_LUMP_SUM.equals(plan) ? "in one sum" : "in instalments on the schedule you were given");
        f.put("holdDays", holdDays);
        f.put("expiresOn", booking != null && booking.getExpiresAt() != null
                ? DAY.format(booking.getExpiresAt().atZoneSameInstant(NAIROBI))
                : "the end of the hold");
        f.put("penaltyBasis", policy.penaltyBasis());
        f.put("penaltyRate", policy.penaltyRate() == null ? null : policy.penaltyRate());
        f.put("penaltyCap", policy.penaltyCap());
        f.put("bankSharePercent", policy.bankSharePercent());
        f.put("penalty", policy.penaltySaid(currency));
        f.put("refundWithinDays", policy.refundWithinDays());
        // With the preposition, so the sentence reads either way: "at any time", "within 90 days".
        f.put("refundWindow", policy.refundWithinDays() <= 0 ? "at any time" : "within " + policy.refundWithinDays() + " days");
        f.put("reviveWithinDays", policy.reviveWithinDays());
        f.put("reviveWindow", policy.reviveWithinDays() <= 0 ? "at any time" : "within " + policy.reviveWithinDays() + " days");
        f.put("policyNote", policy.note() == null || policy.note().isBlank()
                ? "The seller has added nothing to these terms." : policy.note());
        return f;
    }

    /** {@code {{name}}} → the figure; an unknown name stays visible, so a typo in the template shows. */
    static String render(String body, Map<String, Object> figures) {
        return com.hodi.common.util.Placeholders.render(body, figures);
    }

    private BookingTerms requireOpen(UnitBooking booking) {
        BookingTerms row = terms.findCurrent(booking.getId())
                .orElseThrow(() -> new HodiException("No terms were presented for this booking.", HttpStatus.CONFLICT));
        if (row.isDecided()) {
            throw new HodiException("These terms were already " + (row.getAcceptedAt() != null ? "accepted." : "declined."),
                    HttpStatus.CONFLICT);
        }
        if (!booking.isLive()) {
            throw new HodiException("That booking is " + booking.getState().toLowerCase() + ".", HttpStatus.CONFLICT);
        }
        return row;
    }

    private TemplateResponse toResponse(BookingTermsTemplate t) {
        return new TemplateResponse(t.getVersion(), t.getBody(), t.getNote(), t.getCreatedAt(), t.getCreatedBy(),
                List.of("home", "seller", "buyer", "price", "deposit", "plan", "holdDays", "expiresOn", "penalty",
                        "refundWindow", "reviveWindow", "policyNote"));
    }

    private static String money(BigDecimal amount, String currency) {
        return currency + " " + String.format("%,.0f", amount);
    }
}
