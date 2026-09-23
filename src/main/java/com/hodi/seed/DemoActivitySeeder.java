package com.hodi.seed;

import com.hodi.common.util.RrnGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Nine months of a marketplace's life, for a development database.
 *
 * <h2>Why it exists</h2>
 *
 * <p>The dashboard and the analytics page are read against what the database holds, and a development
 * database holds one booking and two payments. A chart drawn over that is a flat line, and nobody can judge
 * a page they cannot see populated. This writes the activity the pages are built to show — buyers, bookings
 * that pay on time and late and not at all, money arriving by prompt and by transfer with the bank's
 * statement behind it, credits nobody has placed, offers accepted and declined, viewings, enquiries,
 * transfers out and a project's spend — over the last nine months, so every panel has something true to say.
 *
 * <h2>What keeps it out of production</h2>
 *
 * <p>Three things, each sufficient. It exists as a bean only when {@code hodi.seed.demo=true}, which nothing
 * sets by default. It refuses unless the datasource is on this machine. And it runs once: a database that
 * already holds a row it wrote is left alone.
 *
 * <h2>Rows, not services</h2>
 *
 * <p>Written with plain SQL rather than through the services, because the services enforce today's rules —
 * a payment must have a statement, a prompt goes to the bank, a booking is made by a signed-in person — and
 * the point here is to describe things that already happened, on their own dates. Every row still satisfies
 * every CHECK constraint, and the counters a service would have maintained are recomputed at the end.
 * Deterministic: the same seed produces the same data each time it is run on an empty database.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hodi.seed.demo", havingValue = "true")
@RequiredArgsConstructor
public class DemoActivitySeeder {

    static final String ACTOR = "demo-seed";
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String BASE32 = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTransaction;
    private final PasswordEncoder passwords;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    private final Random random = new Random(20260921L);
    private final LocalDate today = LocalDate.now();

    // ── the door ──────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnBoot() {
        if (!isLocal(datasourceUrl)) {
            log.warn("Demo seeding refused: the datasource {} is not on this machine", datasourceUrl);
            return;
        }
        // Needs the buyer type and group the catalogue seeder reconciles. On the very first boot of an empty
        // database the two listeners have no fixed order; if that one has not run yet, this one waits a boot.
        if (maybe("select id from user_types where code = 'BUYER'", Long.class) == null) {
            log.info("Demo seeding skipped: the catalogue is not seeded yet; it will run on the next start");
            return;
        }
        Long already = jdbc.queryForObject(
                "select count(*) from unit_bookings where created_by = ?", Long.class, ACTOR);
        if (already != null && already > 0) {
            log.info("Demo activity is already in this database ({} bookings); nothing to do", already);
            return;
        }
        try {
            newTransaction.executeWithoutResult(status -> seed());
            log.info("Demo activity seeded: nine months of bookings, payments, statements, prompts, offers, "
                    + "viewings, enquiries, transfers and spend");
        } catch (RuntimeException e) {
            log.error("Demo seeding failed and was rolled back", e);
        }
    }

    static boolean isLocal(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url.replaceFirst("^jdbc:", "")).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── the world ─────────────────────────────────────────────────────────────

    private record Buyer(long id, String name, String phone, String email) {}
    private record Unit(long id, long developmentId, String developmentName, long tenantId, long unitTypeId,
                        String label, String reference, BigDecimal price) {}
    private record Channel(long typeId, String typeName, Long accountId, String accountIdentifier) {}

    private List<Buyer> buyers;
    private Channel stk;
    private Channel ipn;
    private Long buyerTypeId;
    private Long buyerGroupId;
    private String tenantName;
    private long tenantId;

    void seed() {
        // What the platform already has: the seller, the channels, the buyer type. Nothing here is invented.
        tenantId = one("select tenant_id from developments where reference = 'DV260827DEMO'", Long.class);
        tenantName = one("select name from tenants where id = ?", String.class, tenantId);
        buyerTypeId = one("select id from user_types where code = 'BUYER'", Long.class);
        buyerGroupId = one("select id from user_groups where user_type_code = 'BUYER' and tenant_id is null "
                + "and institution_id is null and status <> 5 order by id limit 1", Long.class);
        stk = channel("COOP_STK_PUSH");
        ipn = channel("COOP_IPN_ACCOUNT");

        buyers = buyers();
        List<Unit> highrise2b = units("DV260827DEMO", "2B");
        List<Unit> studios = units("DV260827DEMO", "STU");
        List<Unit> palm = units("DV260915W8XJ", "2B");

        // ── bookings, in the order they happened ──
        // Already sold on the marketplace: the sale, its schedule and its money, as they would have been.
        int i = 0;
        for (Unit u : highrise2b.stream().filter(u -> "SOLD".equals(saleState(u.id()))).toList()) {
            completedBooking(u, buyers.get(i % buyers.size()), monthsAgo(8 - (i % 5)), 1 + (i % 3));
            i++;
        }
        // Paying now, some behind.
        int b = 3;
        for (Unit u : pick(highrise2b, "AVAILABLE", 6)) agreedBooking(u, buyers.get(b++ % buyers.size()), monthsAgo(7 - (b % 6)));
        for (Unit u : pick(studios, "AVAILABLE", 3)) agreedBooking(u, buyers.get(b++ % buyers.size()), monthsAgo(4 - (b % 3)));
        for (Unit u : pick(palm, "AVAILABLE", 3)) agreedBooking(u, buyers.get(b++ % buyers.size()), monthsAgo(3 - (b % 3)));
        // Held this month: two with a deposit down, two without, two of which lapse this week.
        List<Unit> held = pick(highrise2b, "AVAILABLE", 2);
        held.addAll(pick(palm, "AVAILABLE", 2));
        for (int h = 0; h < held.size(); h++) {
            reservedBooking(held.get(h), buyers.get((b + h) % buyers.size()), today.minusDays(3 + h * 4), h % 2 == 0, h);
        }
        // And the ones that did not happen.
        List<Unit> gone = pick(studios, "AVAILABLE", 2);
        cancelledBooking(gone.get(0), buyers.get(1), monthsAgo(5), "Buyer's mortgage was declined");
        lapsedBooking(gone.get(1), buyers.get(5), monthsAgo(3));

        // ── money that arrived and found no home ──
        unplacedCredits(highrise2b);
        // ── prompts that did not end in a payment ──
        strayPrompts(highrise2b);
        // ── the funnel ──
        enquiries(highrise2b, studios, palm);
        viewings(highrise2b, palm);
        offers(highrise2b, palm);
        // ── the bank's own money going out ──
        disbursements();
        // ── the projects' spend ──
        projectFinance();
        // ── what the services would have kept in step ──
        recount();
    }

    // ── people ────────────────────────────────────────────────────────────────

    private List<Buyer> buyers() {
        String[][] names = {
                {"Wanjiku", "Njoroge"}, {"Brian", "Otieno"}, {"Amina", "Hassan"}, {"Kevin", "Mwangi"},
                {"Grace", "Achieng"}, {"Samuel", "Kiptoo"}, {"Faith", "Wambui"}, {"Dennis", "Ochieng"},
                {"Naomi", "Chebet"}, {"Peter", "Kamau"}, {"Lilian", "Mutua"}, {"George", "Waweru"},
        };
        List<Buyer> out = new ArrayList<>();
        for (int n = 0; n < names.length; n++) {
            String first = names[n][0], last = names[n][1];
            String username = (first + "." + last).toLowerCase() + ".demo";
            String email = username + "@example.invalid";
            String phone = "2547" + (10_000_000 + random.nextInt(89_999_999));
            OffsetDateTime joined = at(monthsAgo(9).plusDays(random.nextInt(60)), 9 + random.nextInt(9));
            Long id = one("""
                    insert into users (first_name, last_name, email, username, phone, password, enabled,
                        email_verified_at, phone_verified_at, status, status_flag, created_at, updated_at, created_by)
                    values (?, ?, ?, ?, ?, ?, true, ?, ?, 1, 'Active', ?, ?, ?) returning id""", Long.class,
                    first, last, email, username, phone, passwords.encode("Demo#" + UUID.randomUUID()),
                    joined, joined, joined, joined, ACTOR);
            jdbc.update("""
                    insert into user_profiles (user_id, profile_type, user_type_id, user_type_code, user_type_name,
                        user_group_id, user_group_name, kyc_status, is_default, status, status_flag, created_at, updated_at, created_by)
                    values (?, 'BUYER', ?, 'BUYER', 'Buyer', ?, 'Buyer', 'NOT_REQUIRED', true, 1, 'Active', ?, ?, ?)""",
                    id, buyerTypeId, buyerGroupId, joined, joined, ACTOR);
            out.add(new Buyer(id, first + " " + last, phone, email));
        }
        return out;
    }

    // ── bookings ──────────────────────────────────────────────────────────────

    /** Sold and paid up: a short plan, every instalment met, the unit marked sold on the last payment. */
    private void completedBooking(Unit u, Buyer who, LocalDate bookedOn, int instalments) {
        BigDecimal price = u.price();
        BigDecimal deposit = pct(price, 20);
        long id = booking(u, who, "COMPLETED", price, deposit, bookedOn, null);
        List<long[]> schedule = schedule(id, bookedOn, deposit, price.subtract(deposit), instalments);
        LocalDate last = bookedOn;
        BigDecimal paid = BigDecimal.ZERO;
        for (long[] line : schedule) {
            LocalDate due = LocalDate.ofEpochDay(line[0]);
            LocalDate on = due.plusDays(random.nextInt(4));
            BigDecimal amount = BigDecimal.valueOf(line[1], 2);
            paid = payment(id, u, who, amount, on, price.subtract(paid));
            last = on;
        }
        jdbc.update("update unit_bookings set agreed_at = ?, completed_at = ?, updated_at = ? where id = ?",
                at(bookedOn.plusDays(2), 11), at(last, 15), at(last, 15), id);
        jdbc.update("""
                update properties set sale_state = 'SOLD', buyer_user_id = ?, buyer_name = ?, buyer_phone = ?, buyer_email = ?,
                    sold_at = ?, sold_price = ?, reserved_at = ?, reserved_until = null, updated_at = ?, updated_by = ?
                where id = ?""", who.id(), who.name(), who.phone(), who.email(), at(last, 15), price,
                at(bookedOn, 10), at(last, 15), ACTOR, u.id());
    }

    /** Agreed and paying: deposit met, twenty-four monthly instalments, most on time, some late, a few missed. */
    private void agreedBooking(Unit u, Buyer who, LocalDate bookedOn) {
        BigDecimal price = u.price();
        BigDecimal deposit = pct(price, 10);
        long id = booking(u, who, "AGREED", price, deposit, bookedOn, null);
        List<long[]> schedule = schedule(id, bookedOn, deposit, price.subtract(deposit), 24);
        BigDecimal paid = BigDecimal.ZERO;
        int missed = 0;
        boolean first = true;
        for (long[] line : schedule) {
            LocalDate due = LocalDate.ofEpochDay(line[0]);
            if (due.isAfter(today)) break;
            BigDecimal amount = BigDecimal.valueOf(line[1], 2);
            int dice = random.nextInt(100);
            LocalDate on;
            if (first || dice < 68) {
                on = due.plusDays(random.nextInt(4));                 // on time, or as near as makes no odds
            } else if (dice < 88 || missed >= 2) {
                on = due.plusDays(5 + random.nextInt(21));            // late
            } else {
                missed++;                                             // never paid: what "buyers behind" is
                continue;
            }
            if (on.isAfter(today)) continue;                          // due, late, and not yet paid
            paid = payment(id, u, who, amount, on, price.subtract(paid));
            first = false;
        }
        jdbc.update("update unit_bookings set agreed_at = ?, updated_at = ? where id = ?",
                at(bookedOn.plusDays(3), 10), at(bookedOn.plusDays(3), 10), id);
        jdbc.update("""
                update properties set sale_state = 'RESERVED', buyer_user_id = ?, buyer_name = ?, buyer_phone = ?, buyer_email = ?,
                    reserved_at = ?, reserved_until = null, updated_at = ?, updated_by = ? where id = ?""",
                who.id(), who.name(), who.phone(), who.email(), at(bookedOn, 10), at(bookedOn.plusDays(3), 10), ACTOR, u.id());
    }

    /** Held this month: the fourteen-day window running, a deposit down or not. Two of them lapse this week. */
    private void reservedBooking(Unit u, Buyer who, LocalDate bookedOn, boolean depositPaid, int n) {
        BigDecimal price = u.price();
        BigDecimal deposit = pct(price, 10);
        LocalDate expires = n < 2 ? today.plusDays(2 + n * 3) : bookedOn.plusDays(14);
        long id = booking(u, who, "RESERVED", price, deposit, bookedOn, at(expires, 23));
        schedule(id, bookedOn, deposit, price.subtract(deposit), 24);
        if (depositPaid) payment(id, u, who, deposit, bookedOn.plusDays(1), price);
        jdbc.update("""
                update properties set sale_state = 'HELD', buyer_user_id = ?, buyer_name = ?, buyer_phone = ?, buyer_email = ?,
                    reserved_at = ?, reserved_until = ?, updated_at = ?, updated_by = ? where id = ?""",
                who.id(), who.name(), who.phone(), who.email(), at(bookedOn, 10), at(expires, 23), at(bookedOn, 10), ACTOR, u.id());
    }

    private void cancelledBooking(Unit u, Buyer who, LocalDate bookedOn, String why) {
        long id = booking(u, who, "CANCELLED", u.price(), pct(u.price(), 10), bookedOn, null, at(bookedOn.plusDays(34), 16), why);
        schedule(id, bookedOn, pct(u.price(), 10), u.price().subtract(pct(u.price(), 10)), 24);
        jdbc.update("update unit_bookings set agreed_at = ? where id = ?", at(bookedOn.plusDays(2), 10), id);
    }

    private void lapsedBooking(Unit u, Buyer who, LocalDate bookedOn) {
        booking(u, who, "LAPSED", u.price(), pct(u.price(), 10), bookedOn, null, at(bookedOn.plusDays(14), 23),
                "The hold ran out with nothing paid.");
    }

    private long booking(Unit u, Buyer who, String state, BigDecimal price, BigDecimal deposit, LocalDate bookedOn,
                         OffsetDateTime expiresAt) {
        return booking(u, who, state, price, deposit, bookedOn, expiresAt, null, null);
    }

    /** A closed booking carries its closing in the same row version: ck_booking_closed ties the two together. */
    private long booking(Unit u, Buyer who, String state, BigDecimal price, BigDecimal deposit, LocalDate bookedOn,
                         OffsetDateTime expiresAt, OffsetDateTime closedAt, String closeReason) {
        String code = payCode();
        long id = one("""
                insert into unit_bookings (reference, pay_reference, development_id, unit_type_id, tenant_id, buyer_user_id, buyer_name,
                    buyer_phone, buyer_email, state, price_agreed, currency, deposit_due, payment_plan, booked_on, expires_at,
                    closed_at, close_reason, notes, status, status_flag, created_at, updated_at, created_by, updated_by, property_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'KES', ?, 'INSTALMENTS', ?, ?, ?, ?, ?, 1, 'Active', ?, ?, ?, ?, ?)
                returning id""", Long.class,
                ref("BK", bookedOn), code, u.developmentId(), u.unitTypeId(), u.tenantId(), who.id(), who.name(), who.phone(),
                who.email(), state, price, deposit, bookedOn, expiresAt, closedAt, closeReason, "Demo activity.",
                at(bookedOn, 10), closedAt == null ? at(bookedOn, 10) : closedAt, ACTOR, ACTOR, u.id());
        payCodes.put(id, code);
        return id;
    }

    /** The code each seeded booking was given, so its statements and payments quote it. */
    private final Map<Long, String> payCodes = new HashMap<>();

    /** A code no booking holds, drawn the way {@code PayCodeAllocator} draws one. */
    private String payCode() {
        for (int attempt = 0; attempt < 25; attempt++) {
            String candidate = RrnGenerator.payCode();
            if (one("select count(*) from unit_bookings where pay_reference = ?", Long.class, candidate) == 0) return candidate;
        }
        throw new IllegalStateException("Could not draw a free pay code");
    }

    /** A deposit on the day, then equal monthly instalments. Returns [dueOn epochDay, amount cents] per line. */
    private List<long[]> schedule(long bookingId, LocalDate bookedOn, BigDecimal deposit, BigDecimal balance, int months) {
        List<long[]> lines = new ArrayList<>();
        BigDecimal each = balance.divide(BigDecimal.valueOf(months), 2, RoundingMode.DOWN);
        BigDecimal lastLine = balance.subtract(each.multiply(BigDecimal.valueOf(months - 1)));
        instalment(bookingId, 1, "Deposit", bookedOn, deposit);
        lines.add(new long[] {bookedOn.toEpochDay(), deposit.movePointRight(2).longValueExact()});
        for (int k = 1; k <= months; k++) {
            BigDecimal amount = k == months ? lastLine : each;
            LocalDate due = bookedOn.plusMonths(k);
            instalment(bookingId, k + 1, "Instalment " + k + " of " + months, due, amount);
            lines.add(new long[] {due.toEpochDay(), amount.movePointRight(2).longValueExact()});
        }
        return lines;
    }

    private void instalment(long bookingId, int seq, String label, LocalDate due, BigDecimal amount) {
        jdbc.update("""
                insert into booking_instalments (booking_id, plan_no, sequence_no, label, due_on, amount, currency, status,
                    created_at, updated_at, created_by)
                values (?, 1, ?, ?, ?, ?, 'KES', 1, now(), now(), ?)""", bookingId, seq, label, due, amount, ACTOR);
    }

    // ── money ─────────────────────────────────────────────────────────────────

    /**
     * One payment, the way it would have arrived: by prompt or transfer with the bank's statement behind it and,
     * for a prompt, the prompt itself; by cash or cheque written down at the desk. Returns the new paid total.
     */
    private BigDecimal payment(long bookingId, Unit u, Buyer who, BigDecimal amount, LocalDate on, BigDecimal balanceBefore) {
        int dice = random.nextInt(100);
        String method; String source; Channel via;
        if (dice < 45) { method = "MOBILE_MONEY"; source = "GATEWAY"; via = stk; }
        else if (dice < 80) { method = "BANK_TRANSFER"; source = "GATEWAY"; via = ipn; }
        else if (dice < 92) { method = "CASH"; source = "MANUAL"; via = null; }
        else { method = "CHEQUE"; source = "MANUAL"; via = null; }

        OffsetDateTime paidAt = at(on, 8 + random.nextInt(10));
        Long statementId = null;
        String bankRef = null;
        if (via != null) {
            bankRef = receipt();
            statementId = one("""
                    insert into coop_statements (ref_no, trace_id, our_reference, trans_type, payment_account_id, account_identifier,
                        reference, amount, currency, phone_no, customer_name, paid_at, state, tenant_id, status, status_flag,
                        created_at, updated_at, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'KES', ?, ?, ?, 'UNMAPPED', null, 1, 'Active', ?, ?, ?) returning id""", Long.class,
                    bankRef, trace(on), ref("ST", on),
                    via == stk ? "STK_QUERY" : "CREDIT", via.accountId(), via.accountIdentifier(), payCodes.get(bookingId), amount,
                    who.phone(), who.name().toUpperCase(), paidAt, paidAt, paidAt, ACTOR);
        }
        BigDecimal balanceAfter = balanceBefore.subtract(amount);
        Long paymentId = one("""
                insert into payments (reference, booking_id, development_id, property_id, tenant_id, development_name, unit_label,
                    buyer_name, buyer_phone, paid_on, amount, currency, source, method, payment_type_id, payment_type_name,
                    quoted_reference, external_reference, payer_name, payer_phone, balance_before, balance_after, statement_id,
                    status, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'KES', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?) returning id""",
                Long.class,
                ref("PY", on), bookingId, u.developmentId(), u.id(), u.tenantId(), u.developmentName(), u.label(),
                who.name(), who.phone(), on, amount, source, method,
                via == null ? manualTypeId(method) : via.typeId(), via == null ? null : via.typeName(),
                via == null ? null : payCodes.get(bookingId), bankRef, who.name(), who.phone(), balanceBefore, balanceAfter, statementId,
                paidAt, paidAt, via == null ? "kamanza" : "system", via == null ? "kamanza" : "system");
        if (statementId != null) {
            jdbc.update("""
                    update coop_statements set state = 'MAPPED', mapped_payment_id = ?, mapped_booking_id = ?, mapped_at = ?,
                        mapped_by = 'system', updated_at = ? where id = ?""", paymentId, bookingId, paidAt, paidAt, statementId);
        }
        if (via == stk) {
            jdbc.update("""
                    insert into payment_intents (reference, payment_type_id, payment_account_id, booking_id, property_id, buyer_user_id,
                        amount, currency, phone_no, narration, state, bank_reference, receipt, processed_at, callback_timeout_seconds,
                        status_query_attempts, processing_reason, statement_id, payment_id, status, status_flag, created_at, created_by,
                        updated_at, updated_by, trace_id)
                    values (?, ?, ?, ?, ?, ?, ?, 'KES', ?, ?, 'SUCCEEDED', ?, ?, ?, 60, 0, ?, ?, ?, 1, 'Active', ?, ?, ?, 'system', ?)""",
                    ref("IN", on), stk.typeId(), stk.accountId(), bookingId, u.id(), who.id(), amount, who.phone(),
                    "Payment for " + u.label(), "IN-" + bankRef, bankRef, paidAt, "Confirmed by Co-op: Success", statementId,
                    paymentId, paidAt.minusMinutes(2), who.name(), paidAt, trace(on));
        }
        return u.price().subtract(balanceAfter);
    }

    /** Credits the bank reported that named no home anybody could find, and two a person set aside. */
    private void unplacedCredits(List<Unit> units) {
        String[][] payers = {
                {"JOHN K MAINA", "254722334455", "B2O4 HIGHRISE"}, {"ESTHER W NJERI", "254733667788", "HOUSE DEPOSIT"},
                {"PATRICK O ODUYA", "254711223344", "9547"}, {"MARY A WAIRIMU", "254700998877", "PAYMENT"},
                {"JAMES M KARIUKI", "254799112233", "B-3-04 KARIUKI"}, {"RUTH N MUTHONI", "254712998811", "C0B7"},
        };
        for (int n = 0; n < payers.length; n++) {
            LocalDate on = today.minusDays(2 + n * 3);
            BigDecimal amount = BigDecimal.valueOf(150_000 + random.nextInt(9) * 100_000L);
            boolean setAside = n >= 4;
            String quoted = payers[n][2];
            String why = setAside
                    ? "Set aside: the sender asked for it back; refund raised with the branch."
                    : quoted.matches("[A-Z0-9]{4}") ? "No unit has the code \"" + quoted + "\"."
                    : "Nothing in \"" + quoted + "\" names a unit, a booking or a listing.";
            jdbc.update("""
                    insert into coop_statements (ref_no, trace_id, our_reference, trans_type, payment_account_id, account_identifier,
                        reference, amount, currency, phone_no, customer_name, paid_at, state, unmapped_reason, tenant_id, status,
                        status_flag, created_at, updated_at, created_by)
                    values (?, ?, ?, 'CREDIT', ?, ?, ?, ?, 'KES', ?, ?, ?, ?, ?, null, 1, 'Active', ?, ?, ?)""",
                    receipt(), trace(on), ref("ST", on),
                    ipn.accountId(), ipn.accountIdentifier(), quoted, amount, payers[n][1], payers[n][0], at(on, 9 + n),
                    setAside ? "IGNORED" : "UNMAPPED", why, at(on, 9 + n), at(on, 9 + n), ACTOR);
        }
    }

    /** Prompts that were declined on the handset, ran out of money, or were never answered. */
    private void strayPrompts(List<Unit> units) {
        String[] failures = {"Request cancelled by user", "Insufficient balance", "Request cancelled by user",
                "The initiator information is invalid", "Request cancelled by user", "DS timeout user cannot be reached"};
        for (int n = 0; n < failures.length; n++) {
            Unit u = units.get(n % units.size());
            Buyer who = buyers.get((n + 2) % buyers.size());
            LocalDate on = today.minusDays(4 + n * 9);
            prompt(u, who, on, "FAILED", "Co-op did not accept the prompt: " + failures[n], 0);
        }
        for (int n = 0; n < 2; n++) {
            Unit u = units.get((n + 7) % units.size());
            prompt(u, buyers.get(n), today.minusDays(1 + n), "PROCESSING",
                    "Asked the bank and could not get an answer. It will be asked again.", 2);
        }
    }

    private void prompt(Unit u, Buyer who, LocalDate on, String state, String reason, int attempts) {
        OffsetDateTime when = at(on, 10 + random.nextInt(8));
        String ref = ref("IN", on);
        jdbc.update("""
                insert into payment_intents (reference, payment_type_id, payment_account_id, property_id, buyer_user_id, amount, currency,
                    phone_no, narration, state, bank_reference, processed_at, callback_timeout_seconds, status_query_attempts,
                    processing_reason, status, status_flag, created_at, created_by, updated_at, updated_by, trace_id)
                values (?, ?, ?, ?, ?, ?, 'KES', ?, ?, ?, ?, ?, 60, ?, ?, 1, 'Active', ?, ?, ?, 'system', ?)""",
                ref, stk.typeId(), stk.accountId(), u.id(), who.id(), pct(u.price(), 10), who.phone(),
                "Payment for " + u.label(), state, ref, "FAILED".equals(state) ? when.plusMinutes(3) : null, attempts, reason,
                when, who.name(), when.plusMinutes(3), trace(on));
    }

    // ── the funnel ────────────────────────────────────────────────────────────

    private void enquiries(List<Unit> a, List<Unit> b, List<Unit> c) {
        String[] subjects = {"Is the price negotiable?", "When is handover?", "Can I view this weekend?",
                "Do you accept a mortgage from another bank?", "What is the service charge?", "Is parking included?",
                "Are pets allowed?", "Can the kitchen be changed before handover?", "What does the deposit hold?",
                "Is there a payment plan longer than two years?"};
        List<Unit> all = new ArrayList<>(a); all.addAll(b); all.addAll(c);
        for (int n = 0; n < 20; n++) {
            Unit u = all.get(random.nextInt(all.size()));
            Buyer who = buyers.get(n % buyers.size());
            LocalDate on = today.minusDays(n < 5 ? 1 + n * 2 : 12 + random.nextInt(240));
            String state = n < 5 ? "OPEN" : n < 14 ? "ANSWERED" : "CLOSED";
            OffsetDateTime asked = at(on, 8 + random.nextInt(11));
            OffsetDateTime replied = asked.plusHours(2 + random.nextInt(40));
            int messages = "OPEN".equals(state) ? 1 : 2 + random.nextInt(3);
            Long id = one("""
                    insert into enquiry_tickets (reference, tenant_id, tenant_name, property_id, property_reference, property_title,
                        user_id, buyer_name, buyer_email, buyer_phone, subject, state, message_count, last_message_at, last_message_side,
                        awaiting_seller, closed_at, close_reason, status, status_flag, created_at, updated_at, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'Active', ?, ?, ?) returning id""", Long.class,
                    ref("EN", on), u.tenantId(), tenantName, u.id(), u.reference(), u.developmentName() + " · " + u.label(),
                    who.id(), who.name(), who.email(), who.phone(), subjects[n % subjects.length], state, messages,
                    "OPEN".equals(state) ? asked : replied, "OPEN".equals(state) ? "BUYER" : "SELLER", "OPEN".equals(state),
                    "CLOSED".equals(state) ? replied.plusDays(1) : null, "CLOSED".equals(state) ? "Answered in full." : null,
                    asked, "OPEN".equals(state) ? asked : replied, who.name());
            jdbc.update("insert into enquiry_messages (ticket_id, author_side, author_user_id, author_name, body, created_at) values (?, 'BUYER', ?, ?, ?, ?)",
                    id, who.id(), who.name(), subjects[n % subjects.length] + " I am looking at " + u.label() + ".", asked);
            if (!"OPEN".equals(state)) {
                jdbc.update("insert into enquiry_messages (ticket_id, author_side, author_user_id, author_name, body, created_at) values (?, 'SELLER', null, ?, ?, ?)",
                        id, "Sales office", "Thank you for asking. Yes — happy to talk it through; call us or arrange a viewing.", replied);
            }
        }
    }

    private void viewings(List<Unit> a, List<Unit> b) {
        List<Unit> all = new ArrayList<>(a); all.addAll(b);
        String[] states = {"REQUESTED", "REQUESTED", "REQUESTED", "CONFIRMED", "CONFIRMED", "CONFIRMED",
                "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED",
                "DECLINED", "DECLINED", "CANCELLED"};
        for (int n = 0; n < states.length; n++) {
            Unit u = all.get(random.nextInt(all.size()));
            Buyer who = buyers.get((n + 4) % buyers.size());
            String state = states[n];
            LocalDate on = switch (state) {
                case "REQUESTED" -> today.minusDays(1 + n);
                case "CONFIRMED" -> today.minusDays(2 + n);
                default -> today.minusDays(20 + random.nextInt(230));
            };
            OffsetDateTime requested = at(on, 9 + random.nextInt(9));
            OffsetDateTime slot = "CONFIRMED".equals(state) ? at(today.plusDays(1 + n % 6), 10 + (n % 3) * 2)
                    : "COMPLETED".equals(state) ? at(on.plusDays(3), 10 + (n % 3) * 2) : null;
            OffsetDateTime decided = "REQUESTED".equals(state) || "CANCELLED".equals(state) ? null : requested.plusHours(5);
            jdbc.update("""
                    insert into site_visits (reference, tenant_id, tenant_name, property_id, property_reference, property_title, user_id,
                        buyer_name, buyer_email, buyer_phone, requested_at, slot_at, party_size, buyer_note, state, seller_note,
                        decided_by_user_id, decided_at, outcome_note, status, status_flag, created_at, updated_at, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'Active', ?, ?, ?)""",
                    ref("VS", on), u.tenantId(), tenantName, u.id(), u.reference(), u.developmentName() + " · " + u.label(),
                    who.id(), who.name(), who.email(), who.phone(), requested, slot, (short) (1 + random.nextInt(3)),
                    n % 3 == 0 ? "Coming with my spouse." : null, state,
                    "DECLINED".equals(state) ? "No viewings on that date — the site is closed." : "CONFIRMED".equals(state) ? "See you at the site office." : null,
                    decided == null ? null : 1L, decided,
                    "COMPLETED".equals(state) ? (n % 2 == 0 ? "Liked it; asked about the payment plan." : "Wanted a higher floor.") : null,
                    requested, decided == null ? requested : decided, who.name());
        }
    }

    private void offers(List<Unit> a, List<Unit> b) {
        List<Unit> all = new ArrayList<>(a); all.addAll(b);
        String[] states = {"SUBMITTED", "SUBMITTED", "SUBMITTED", "UNDER_REVIEW", "UNDER_REVIEW",
                "ACCEPTED", "ACCEPTED", "ACCEPTED", "ACCEPTED", "DECLINED", "DECLINED", "DECLINED", "DECLINED", "WITHDRAWN"};
        String[] financing = {"MORTGAGE", "CASH", "MORTGAGE", "PART_EXCHANGE", "MORTGAGE"};
        int accepted = 0;
        for (int n = 0; n < states.length; n++) {
            String state = states[n];
            boolean becomesBooking = "ACCEPTED".equals(state) && accepted < 2;
            Unit u = becomesBooking ? pick(all, "AVAILABLE", 1).get(0) : all.get((n * 3) % all.size());
            Buyer who = buyers.get((n + 6) % buyers.size());
            LocalDate on = "SUBMITTED".equals(state) || "UNDER_REVIEW".equals(state) ? today.minusDays(1 + n * 2)
                    : today.minusDays(15 + random.nextInt(220));
            BigDecimal asking = u.price();
            BigDecimal offer = asking.multiply(BigDecimal.valueOf(88 + random.nextInt(13))).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            boolean decided = !("SUBMITTED".equals(state) || "UNDER_REVIEW".equals(state) || "WITHDRAWN".equals(state));
            OffsetDateTime made = at(on, 9 + random.nextInt(10));
            Long id = one("""
                    insert into purchase_requests (reference, tenant_id, tenant_name, property_id, property_reference, property_title, asking_price, user_id, buyer_name, buyer_email, buyer_phone, offer_amount, original_amount, currency, financing, deposit_available, buyer_message, state, decided_by_user_id, decided_at, decision_note, status, status_flag, created_at, updated_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'KES', ?, ?, ?, ?, ?, ?, ?, 1, 'Active', ?, ?, ?) returning id""", Long.class,
                    ref("OF", on), u.tenantId(), tenantName, u.id(), u.reference(), u.developmentName() + " · " + u.label(),
                    asking, who.id(), who.name(), who.email(), who.phone(), offer, offer, financing[n % financing.length],
                    pct(offer, 10 + random.nextInt(15)), n % 2 == 0 ? "Pre-approved with my bank; can complete in 90 days." : null,
                    state, decided ? 1L : null, decided ? made.plusDays(1 + random.nextInt(4)) : null,
                    "DECLINED".equals(state) ? "Too far below asking for this floor." : "ACCEPTED".equals(state) ? "Agreed, subject to the deposit within 14 days." : null,
                    made, decided ? made.plusDays(2) : made, who.name());
            jdbc.update("insert into lead_messages (lead_type, lead_id, author_side, author_user_id, author_name, body, state_after, status, status_flag, created_at, created_by) values ('PURCHASE_REQUEST', ?, 'BUYER', ?, ?, ?, 'SUBMITTED', 1, 'Active', ?, ?)",
                    id, who.id(), who.name(), "Offered KES " + offer.toPlainString() + ".", made, who.name());
            // Two accepted offers became bookings: the ones the sales office followed through on.
            if (becomesBooking) {
                accepted++;
                long bookingId = booking(u, who, "AGREED", offer, pct(offer, 10), on.plusDays(3), null);
                schedule(bookingId, on.plusDays(3), pct(offer, 10), offer.subtract(pct(offer, 10)), 24);
                payment(bookingId, u, who, pct(offer, 10), on.plusDays(4), offer);
                jdbc.update("update unit_bookings set agreed_at = ?, notes = ?, updated_at = ? where id = ?",
                        at(on.plusDays(5), 10), "From offer " + one("select reference from purchase_requests where id = ?", String.class, id),
                        at(on.plusDays(5), 10), bookingId);
                jdbc.update("update purchase_requests set booking_id = ? where id = ?", bookingId, id);
                jdbc.update("""
                        update properties set sale_state = 'RESERVED', buyer_user_id = ?, buyer_name = ?, buyer_phone = ?, buyer_email = ?,
                            reserved_at = ?, reserved_until = null, updated_at = ?, updated_by = ? where id = ? and sale_state = 'AVAILABLE'""",
                        who.id(), who.name(), who.phone(), who.email(), at(on.plusDays(3), 10), at(on.plusDays(5), 10), ACTOR, u.id());
            }
        }
    }

    // ── money out ─────────────────────────────────────────────────────────────

    private void disbursements() {
        Long pesalinkType = one("select id from payment_types where provider_type = 'COOP_PESALINK'", Long.class);
        Long source = maybe("select id from payment_accounts where payment_type_id = ? and tenant_id is null and status in (1,2) order by id limit 1",
                Long.class, pesalinkType);
        if (source == null) {
            source = one("""
                    insert into payment_accounts (payment_type_id, account_no, account_name, provider_code, category, status, status_flag,
                        config, created_at, updated_at, created_by)
                    values (?, '01100098765432', 'Hodi disbursements', 'COOP_PESALINK', 'TRANSFER', 1, 'Active', '{}'::jsonb, now(), now(), ?)
                    returning id""", Long.class, pesalinkType, ACTOR);
        }
        Long maker = maybe("select id from users where username = 'otieno'", Long.class);
        String makerName = maker == null ? "superadmin" : "otieno";
        if (maker == null) maker = 1L;

        Object[][] rows = {
                // payeeKind, tenant, payee, account, validatedName, amount, purpose, state, daysAgo, code, description
                {"SELLER_ORGANISATION", tenantId, tenantName, "01102901454001", "ACACIA RIDGE DEVELOPMENTS LTD", 4_850_000, "Stage 2 certificate — Highrise Block B", "SUCCEEDED", 62, "0", "Success"},
                {"OTHER", null, "Mwangi & Daughters Contractors", "01192200338811", "MWANGI AND DAUGHTERS CONTRACTORS", 2_300_000, "Fit-out invoice 2026-041", "SUCCEEDED", 41, "0", "Success"},
                {"SELLER_ORGANISATION", tenantId, tenantName, "01102901454001", "ACACIA RIDGE DEVELOPMENTS LTD", 6_120_000, "Stage 3 certificate — Highrise Block B", "SUCCEEDED", 19, "0", "Success"},
                {"OTHER", null, "Kilimani Quantity Surveyors", "01128877665544", "KILIMANI QUANTITY SURVEYORS LLP", 780_000, "QS fees, August", "FAILED", 9, "-5", "Insufficient balance"},
                {"SELLER_ORGANISATION", tenantId, tenantName, "01102901454001", "ACACIA RIDGE DEVELOPMENTS LTD", 3_400_000, "Buyer refund — cancelled booking, studio 104", "AWAITING_APPROVAL", 0, null, null},
        };
        for (Object[] r : rows) {
            LocalDate on = today.minusDays((Integer) r[8]);
            OffsetDateTime made = at(on, 10);
            String state = (String) r[7];
            boolean decided = !"AWAITING_APPROVAL".equals(state);
            Long id = one("""
                    insert into disbursements (reference, payee_kind, tenant_id, payee_name, bank_code, account_no, validated_name, validated_at,
                        amount, currency, purpose, narration, source_account_id, state, bank_reference, response_code, response_description,
                        sent_at, settled_at, callback_timeout_seconds, status_query_attempts, processing_reason, made_by, checked_by, checked_at,
                        status, status_flag, created_at, updated_at, created_by, updated_by)
                    values (?, ?, ?, ?, '0011', ?, ?, ?, ?, 'KES', ?, ?, ?, ?, ?, ?, ?, ?, ?, 300, 0, ?, ?, ?, ?, 1, 'Active', ?, ?, ?, ?) returning id""",
                    Long.class,
                    ref("DB", on), r[0], r[1], r[2], r[3], r[4], made.minusMinutes(5), BigDecimal.valueOf((Integer) r[5]), r[6],
                    ((String) r[6]).length() > 160 ? ((String) r[6]).substring(0, 160) : r[6], source, state,
                    "SUCCEEDED".equals(state) ? "FT" + digits(12) : null, r[9], r[10],
                    decided ? made.plusHours(3) : null, decided ? made.plusHours(3).plusMinutes(7) : null,
                    "SUCCEEDED".equals(state) ? "Confirmed by Co-op: Success" : "FAILED".equals(state) ? "Co-op says it did not go through: Insufficient balance" : null,
                    makerName, decided ? "superadmin" : null, decided ? made.plusHours(3) : null,
                    made, decided ? made.plusHours(3).plusMinutes(7) : made, makerName, decided ? "system" : makerName);
            if (!decided) {
                jdbc.update("""
                        insert into approval_workflows (entity_type, entity_id, action, subject_label, submitted_by_user_id, submitted_by_username,
                            submitted_at, submission_note, state, status, status_flag, created_at, updated_at, created_by, after_payload, field_labels)
                        values ('DISBURSEMENT', ?, 'SEND', ?, ?, ?, ?, ?, 'PENDING', 1, 'Active', ?, ?, ?, ?::jsonb, ?::jsonb)""",
                        id, "KES 3,400,000.00 to " + r[4], maker, makerName, made,
                        "Money out. Check the name Co-op resolved the account to against who is meant to be paid.", made, made, makerName,
                        "{\"amount\":\"KES 3,400,000.00\",\"payee\":\"" + r[2] + "\",\"account\":\"" + r[3] + " at bank 0011\","
                                + "\"validatedName\":\"" + r[4] + "\",\"purpose\":\"" + r[6] + "\",\"madeBy\":\"" + makerName + "\"}",
                        "{\"amount\":\"Amount\",\"payee\":\"Paid to\",\"account\":\"Account\",\"validatedName\":\"Account held by (Co-op)\","
                                + "\"purpose\":\"Purpose\",\"madeBy\":\"Proposed by\"}");
            }
        }
    }

    // ── the projects ──────────────────────────────────────────────────────────

    private void projectFinance() {
        Long highrise = one("select id from developments where reference = 'DV260827DEMO'", Long.class);
        Long palm = one("select id from developments where reference = 'DV260915W8XJ'", Long.class);
        Map<String, Long> category = Map.of(
                "CONSTRUCTION", one("select id from development_cost_categories where code = 'CONSTRUCTION'", Long.class),
                "PROFESSIONAL_FEES", one("select id from development_cost_categories where code = 'PROFESSIONAL_FEES'", Long.class),
                "STATUTORY", one("select id from development_cost_categories where code = 'STATUTORY'", Long.class),
                "MARKETING", one("select id from development_cost_categories where code = 'MARKETING'", Long.class),
                "FINANCE_COSTS", one("select id from development_cost_categories where code = 'FINANCE_COSTS'", Long.class));

        // Highrise: a budget it never had, planned spend on its phases, and eight months of bills.
        jdbc.update("update developments set budget_amount = coalesce(budget_amount, 180000000), facility_reference = coalesce(facility_reference, 'CF-2025-0342'), "
                + "facility_amount = coalesce(facility_amount, 120000000), started_on = coalesce(started_on, ?), updated_at = now() where id = ?",
                LocalDate.of(2024, 9, 1), highrise);
        jdbc.update("update development_phases set planned_spend = coalesce(planned_spend, case sequence_no when 1 then 38000000 when 2 then 62000000 when 3 then 46000000 else 34000000 end) where development_id = ? and status <> 5",
                highrise);
        spend(highrise, category, new Object[][] {
                {"CONSTRUCTION", 8, 9_400_000, "Kilimani Build Co", "Certificate 14"}, {"CONSTRUCTION", 7, 8_750_000, "Kilimani Build Co", "Certificate 15"},
                {"PROFESSIONAL_FEES", 7, 1_200_000, "Muriuki Associates Architects", "Stage D fees"}, {"CONSTRUCTION", 6, 9_900_000, "Kilimani Build Co", "Certificate 16"},
                {"STATUTORY", 5, 640_000, "Nairobi City County", "Occupation permit, Block A"}, {"CONSTRUCTION", 4, 10_300_000, "Kilimani Build Co", "Certificate 17"},
                {"MARKETING", 3, 850_000, "Bright Signal Media", "Q2 campaign"}, {"CONSTRUCTION", 2, 9_100_000, "Kilimani Build Co", "Certificate 18"},
                {"FINANCE_COSTS", 1, 1_450_000, "Co-operative Bank", "Interest, August"}, {"CONSTRUCTION", 0, 7_800_000, "Kilimani Build Co", "Certificate 19"},
        });
        drawdowns(highrise, new int[][] {{8, 25_000_000}, {5, 30_000_000}, {2, 20_000_000}});

        // Palm Heights: the budget it has, a facility, and the first months of a fresh build.
        jdbc.update("update developments set facility_reference = coalesce(facility_reference, 'CF-2026-0117'), facility_amount = coalesce(facility_amount, 150000000), "
                + "started_on = coalesce(started_on, ?), updated_at = now() where id = ?", LocalDate.of(2026, 3, 1), palm);
        spend(palm, category, new Object[][] {
                {"PROFESSIONAL_FEES", 6, 3_600_000, "Muriuki Associates Architects", "Design fees"}, {"STATUTORY", 5, 2_100_000, "NEMA", "EIA licence"},
                {"CONSTRUCTION", 4, 14_500_000, "Coastline Builders", "Certificate 1"}, {"CONSTRUCTION", 3, 16_200_000, "Coastline Builders", "Certificate 2"},
                {"CONSTRUCTION", 2, 15_800_000, "Coastline Builders", "Certificate 3"}, {"MARKETING", 1, 1_300_000, "Bright Signal Media", "Launch"},
                {"CONSTRUCTION", 0, 17_400_000, "Coastline Builders", "Certificate 4"},
        });
        drawdowns(palm, new int[][] {{4, 40_000_000}, {1, 35_000_000}});
    }

    private void spend(Long development, Map<String, Long> category, Object[][] lines) {
        Long tenant = one("select tenant_id from developments where id = ?", Long.class, development);
        for (Object[] l : lines) {
            LocalDate on = monthsAgo((Integer) l[1]).plusDays(5 + random.nextInt(18));
            jdbc.update("""
                    insert into development_expenditures (reference, development_id, category_id, kind, amount, currency, incurred_on, payee,
                        reference_no, tenant_id, status, status_flag, created_at, updated_at, created_by)
                    values (?, ?, ?, 'SPENT', ?, 'KES', ?, ?, ?, ?, 1, 'Active', ?, ?, ?)""",
                    ref("EX", on), development, category.get((String) l[0]), BigDecimal.valueOf((Integer) l[2]), on, l[3], l[4],
                    tenant, at(on, 14), at(on, 14), ACTOR);
        }
    }

    private void drawdowns(Long development, int[][] draws) {
        Long tenant = one("select tenant_id from developments where id = ?", Long.class, development);
        for (int[] d : draws) {
            LocalDate on = monthsAgo(d[0]).plusDays(2 + random.nextInt(6));
            jdbc.update("""
                    insert into facility_drawdowns (reference, development_id, amount, currency, drawn_on, reference_no, tenant_id, status,
                        status_flag, created_at, updated_at, created_by)
                    values (?, ?, ?, 'KES', ?, ?, ?, 1, 'Active', ?, ?, ?)""",
                    ref("DD", on), development, BigDecimal.valueOf(d[1]), on, "DRW-" + digits(6), tenant, at(on, 11), at(on, 11), ACTOR);
        }
    }

    /**
     * The counters the services keep in step, recomputed from the units as they now stand.
     *
     * <p>Three levels, the way {@code DevelopmentInventoryService.recountUnitType} cascades: the typology,
     * the listing that mirrors it on the marketplace, and the development. Only typologies that have unit
     * rows are touched — an off-plan kind published as "60 homes, 31 left" before a single row exists keeps
     * the seller's figures, because a count of nothing is not a count.
     */
    private void recount() {
        String tally = """
                select u.unit_type_id, u.development_id,
                       count(*) total,
                       count(*) filter (where u.sale_state = 'AVAILABLE') available,
                       count(*) filter (where u.sale_state in ('HELD', 'RESERVED')) reserved,
                       count(*) filter (where u.sale_state = 'SOLD') sold
                  from properties u join developments d on d.id = u.development_id
                 where u.listing_kind = 'UNIT' and u.status <> 5 and d.reference in ('DV260827DEMO', 'DV260915W8XJ')
                 group by u.unit_type_id, u.development_id""";
        jdbc.update("""
                update development_unit_types t set
                    units_total = s.total, units_available = s.available, units_reserved = s.reserved, units_sold = s.sold,
                    updated_at = now()
                from (%s) s where s.unit_type_id = t.id""".formatted(tally));
        jdbc.update("""
                update properties p set units_total = s.total, units_available = s.available, updated_at = now()
                from (%s) s where s.unit_type_id = p.unit_type_id and p.listing_kind = 'TYPOLOGY' and p.status <> 5""".formatted(tally));
        jdbc.update("""
                update developments d set
                    units_total = s.total, units_available = s.available, units_reserved = s.reserved, units_sold = s.sold, updated_at = now()
                from (select development_id, sum(total) total, sum(available) available, sum(reserved) reserved, sum(sold) sold
                        from (%s) x group by development_id) s
                where s.development_id = d.id""".formatted(tally));
    }

    // ── small things ──────────────────────────────────────────────────────────

    private Channel channel(String provider) {
        Long typeId = one("select id from payment_types where provider_type = ? and status <> 5", Long.class, provider);
        String name = one("select name from payment_types where id = ?", String.class, typeId);
        Long account = maybe("select id from payment_accounts where payment_type_id = ? and tenant_id is null and institution_id is null "
                + "and status in (1,2) order by id limit 1", Long.class, typeId);
        String identifier = account == null ? null
                : one("select coalesce(account_no, pay_bill_no, short_code) from payment_accounts where id = ?", String.class, account);
        return new Channel(typeId, name, account, identifier);
    }

    private Long manualTypeId(String method) {
        return one("select id from payment_types where provider_type is null and upper(name) = ? and status <> 5 order by id limit 1",
                Long.class, "CASH".equals(method) ? "CASH" : "CHEQUE");
    }

    private List<Unit> units(String developmentReference, String typeCode) {
        return jdbc.query("""
                select p.id, p.development_id, d.name, d.tenant_id, p.unit_type_id, p.unit_label, p.reference,
                       coalesce(p.price, t.list_price) price
                  from properties p
                  join developments d on d.id = p.development_id
                  join development_unit_types t on t.id = p.unit_type_id
                 where d.reference = ? and t.code = ? and p.listing_kind = 'UNIT' and p.status <> 5
                 order by p.unit_label""",
                (rs, n) -> new Unit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6),
                        rs.getString(7), rs.getBigDecimal(8)),
                developmentReference, typeCode);
    }

    private String saleState(long unitId) {
        return one("select sale_state from properties where id = ?", String.class, unitId);
    }

    /** The first units in the given state that nothing here has taken yet; each is taken once. */
    private List<Unit> pick(List<Unit> from, String state, int count) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : from) {
            if (out.size() == count) break;
            if (state.equals(saleState(u.id())) && !taken.contains(u.id())) { out.add(u); taken.add(u.id()); }
        }
        return out;
    }
    private final java.util.Set<Long> taken = new java.util.HashSet<>();

    private LocalDate monthsAgo(int months) {
        return today.minusMonths(months).withDayOfMonth(Math.min(today.getDayOfMonth(), 27));
    }

    private static OffsetDateTime at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(Math.min(hour, 23), 0)).atOffset(ZoneOffset.ofHours(3));
    }

    private static BigDecimal pct(BigDecimal of, int percent) {
        return of.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** The platform's own shape: a prefix, the day, and four characters. */
    private String ref(String prefix, LocalDate on) {
        StringBuilder sb = new StringBuilder(prefix).append(on.format(YYMMDD));
        for (int i = 0; i < 4; i++) sb.append(BASE32.charAt(random.nextInt(BASE32.length())));
        return sb.toString();
    }

    /** What a Co-op receipt looks like. */
    private String receipt() {
        StringBuilder sb = new StringBuilder("T");
        for (int i = 0; i < 9; i++) sb.append("ABCDEFGHIJKLMNPQRSTUVWXYZ0123456789".charAt(random.nextInt(35)));
        return sb.toString();
    }

    /** The shape the request log stamps: the day, a time, twelve digits. */
    private String trace(LocalDate on) {
        return "HDI" + on.format(DateTimeFormatter.ofPattern("dd"))
                + String.format("%02d%02d", 8 + random.nextInt(10), random.nextInt(60)) + digits(12);
    }

    private String digits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    private <T> T one(String sql, Class<T> type, Object... args) {
        return jdbc.queryForObject(sql, type, args);
    }

    /** A lookup that may find nothing. */
    private <T> T maybe(String sql, Class<T> type, Object... args) {
        try {
            return jdbc.queryForObject(sql, type, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
