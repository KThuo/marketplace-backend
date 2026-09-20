package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.CsvRows;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopIpnService;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.payments.StatementDtos.UploadColumn;
import com.hodi.modules.payments.StatementDtos.UploadLine;
import com.hodi.modules.payments.StatementDtos.UploadOutcome;
import com.hodi.modules.payments.StatementDtos.UploadSpec;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A bank statement, uploaded as CSV, one row becoming one statement — and placed where it names a booking.
 *
 * <h2>Every row goes through the same door as a notification</h2>
 *
 * <p>An uploaded row is stored exactly as a Co-op notification is: same table, same idempotency on the
 * bank's reference, same matcher ({@link CoopIpnService#placeAutomatically}), same writer for the payment.
 * A row quoting a booking reference or a listing reference places itself; one quoting a four-character
 * code places itself when the amount or phone agrees; anything else lands in the unused queue with the
 * reason, where the same screen deals with it. There is no upload-only path that could drift from the
 * rule the notifications follow.
 *
 * <h2>One transaction per row</h2>
 *
 * <p>Through the {@code newTransaction} template, not an annotation: a self-call never reaches the proxy,
 * and one constraint violation on row 40 would otherwise roll back the 39 credits before it. Storing and
 * placing are two transactions, so a row that cannot be placed is still stored with the reason.
 *
 * <h2>What the person is told</h2>
 *
 * <p>Per line: placed, queued, skipped or failed, and a sentence. A reference already on record is a skip
 * that says where the money went, never a failure — the bank exports the same week twice as a matter of
 * course, and the second export must be harmless.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementUploadService {

    /** The account the statement is for. Uploads say which of our accounts the export came from. */
    public static final String TRANS_TYPE = "UPLOAD";

    public static final String REF_NO = "REF NO";
    public static final String AMOUNT = "AMOUNT";
    public static final String PAID_ON = "PAID ON";
    public static final String REFERENCE = "REFERENCE";
    public static final String PAID_BY = "PAID BY";
    public static final String PHONE = "PHONE";
    public static final String NARRATION = "NARRATION";

    public static final List<UploadColumn> COLUMNS = List.of(
            new UploadColumn(REF_NO, true, "FT26092011223344",
                    "The bank's own transaction reference. Unique; a row already on record is skipped."),
            new UploadColumn(AMOUNT, true, "950000.00", "The credit, above zero. Commas are fine."),
            new UploadColumn(PAID_ON, false, "2026-09-18",
                    "When the money landed: yyyy-MM-dd, dd/MM/yyyy, or either with a time. Today if blank."),
            new UploadColumn(REFERENCE, false, "BK260918ABCD",
                    "What the payer quoted: the booking reference, the listing reference or the "
                            + "four-character code. A booking or listing reference places the row on its own."),
            new UploadColumn(PAID_BY, false, "Asha Mwangi", "The payer's name as the bank shows it."),
            new UploadColumn(PHONE, false, "254712345678",
                    "The payer's phone, where the bank shows one. Corroborates a four-character code."),
            new UploadColumn(NARRATION, false, "Deposit for S-2-02", "Anything else the bank said. Kept."));

    /** Other spellings a bank's export uses for the same column. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("REFNO", REF_NO), Map.entry("REF_NO", REF_NO), Map.entry("TRANSACTION ID", REF_NO),
            Map.entry("TRANSACTION REF", REF_NO), Map.entry("TRANSACTIONID", REF_NO),
            Map.entry("BANK REF", REF_NO), Map.entry("BANK REFERENCE", REF_NO),
            Map.entry("CREDITED ON", PAID_ON), Map.entry("DATE", PAID_ON), Map.entry("PAID_ON", PAID_ON),
            Map.entry("VALUE DATE", PAID_ON), Map.entry("TRANSACTION DATE", PAID_ON),
            Map.entry("BOOKING REF", REFERENCE), Map.entry("BOOKING REFERENCE", REFERENCE),
            Map.entry("HOUSE CODE", REFERENCE), Map.entry("CODE", REFERENCE),
            Map.entry("PAYER REFERENCE", REFERENCE), Map.entry("ACCOUNT REFERENCE", REFERENCE),
            Map.entry("PAID_BY", PAID_BY), Map.entry("PAYER", PAID_BY), Map.entry("NAME", PAID_BY),
            Map.entry("CUSTOMER NAME", PAID_BY),
            Map.entry("PHONE NO", PHONE), Map.entry("MSISDN", PHONE), Map.entry("MOBILE", PHONE),
            Map.entry("DESCRIPTION", NARRATION), Map.entry("DETAILS", NARRATION));

    private static final int MAX_ROWS = 5_000;

    private final CoopStatementRepository statements;
    private final PaymentAccountRepository accounts;
    private final PaymentTypeRepository types;
    private final UnitBookingRepository bookings;
    private final CoopIpnService placer;
    private final AuditService audit;
    private final TransactionTemplate newTransaction;
    private final ObjectMapper mapper;

    // ── describing ────────────────────────────────────────────────────────────

    /** The columns, so the form can say what it wants before anything is downloaded. */
    public UploadSpec spec() {
        return new UploadSpec(COLUMNS, MAX_ROWS,
                "One row per credit on the bank statement. The first line is the header; column order does "
                        + "not matter and other spellings of the headings are recognised.");
    }

    /** The header and one example row, as a file a person opens and fills in. */
    public String template() {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", COLUMNS.stream().map(c -> CsvRows.escape(c.key())).toList())).append('\n');
        out.append(String.join(",", COLUMNS.stream().map(c -> CsvRows.escape(c.example())).toList())).append('\n');
        return out.toString();
    }

    // ── taking it in ──────────────────────────────────────────────────────────

    /**
     * Reads the file and records every row, placing what it can.
     *
     * @param accountHash the account the statement is for — which of ours the money landed in
     */
    public UploadOutcome upload(String accountHash, MultipartFile file) {
        String by = AuthContext.username();
        PaymentAccount account = accounts.findById(HashIdUtil.decodeId(accountHash))
                .filter(PaymentAccount::isLive)
                .orElseThrow(() -> new HodiException("Choose the account this statement is for.",
                        HttpStatus.BAD_REQUEST));
        PaymentType channel = types.findById(account.getPaymentTypeId())
                .orElseThrow(() -> new HodiException("That account's payment method no longer exists.",
                        HttpStatus.CONFLICT));
        if (channel.isManual() || !channel.channelCategory().isReceivable()) {
            throw new HodiException(channel.getName() + " is not an account a bank statement comes from.",
                    HttpStatus.BAD_REQUEST);
        }

        List<List<String>> rows = read(file);
        if (rows.isEmpty()) throw new HodiException("The file is empty.", HttpStatus.BAD_REQUEST);
        if (rows.size() - 1 > MAX_ROWS) {
            throw new HodiException("At most " + MAX_ROWS + " rows per file. Split the statement.",
                    HttpStatus.BAD_REQUEST);
        }
        Map<String, Integer> columns = header(rows.get(0));

        List<UploadLine> lines = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        int placed = 0, queued = 0, skipped = 0, failed = 0;
        for (int i = 1; i < rows.size(); i++) {
            int line = i + 1;
            Map<String, String> cells = cells(rows.get(i), columns);
            UploadLine result = take(line, cells, account, seenInFile, by);
            lines.add(result);
            switch (result.outcome()) {
                case "PLACED" -> placed++;
                case "QUEUED" -> queued++;
                case "SKIPPED" -> skipped++;
                default -> failed++;
            }
        }

        UploadOutcome outcome = new UploadOutcome(rows.size() - 1, placed, queued, skipped, failed, lines);
        audit.record(AppConstant.AUDIT_STATEMENT_UPLOADED, "PaymentAccount", account.getId(), null,
                (file.getOriginalFilename() == null ? "statement.csv" : file.getOriginalFilename())
                        + ": " + outcome.rows() + " rows, " + placed + " placed, " + queued + " queued, "
                        + skipped + " skipped, " + failed + " failed");
        log.info("Statement upload for account {} by {}: {} rows, {} placed, {} queued, {} skipped, {} failed",
                account.getAccountNo(), by, outcome.rows(), placed, queued, skipped, failed);
        return outcome;
    }

    /** One row: stored in its own transaction, then placed in another, so a failure to place keeps the row. */
    private UploadLine take(int line, Map<String, String> cells, PaymentAccount account, Set<String> seen,
                            String by) {
        String refNo = blankToNull(cells.get(REF_NO));
        if (refNo == null) return UploadLine.failed(line, null, "No " + REF_NO + ".");
        if (refNo.length() > 64) return UploadLine.failed(line, refNo, REF_NO + " is longer than 64 characters.");
        if (!seen.add(refNo.toUpperCase(Locale.ROOT))) {
            return UploadLine.skipped(line, refNo, "Appears earlier in this file.");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(cells.getOrDefault(AMOUNT, "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            return UploadLine.failed(line, refNo, "Amount \"" + cells.get(AMOUNT) + "\" is not a number.");
        }
        if (amount.signum() <= 0) return UploadLine.failed(line, refNo, "Amount must be above zero.");

        OffsetDateTime paidAt;
        try {
            paidAt = paidAt(cells.get(PAID_ON));
        } catch (DateTimeParseException e) {
            return UploadLine.failed(line, refNo, "Date \"" + cells.get(PAID_ON)
                    + "\" is not yyyy-MM-dd or dd/MM/yyyy.");
        }

        CoopStatement existing = statements.findByRefNo(refNo).orElse(null);
        if (existing != null) return UploadLine.skipped(line, refNo, alreadyRecorded(existing));

        CoopStatement stored;
        try {
            stored = newTransaction.execute(status -> statements.saveAndFlush(CoopStatement.builder()
                    .refNo(refNo)
                    .ourReference(RrnGenerator.generate("PS"))
                    .transType(TRANS_TYPE)
                    .paymentAccountId(account.getId())
                    .accountIdentifier(account.getAccountNo())
                    .reference(clip(blankToNull(cells.get(REFERENCE)), 64))
                    .amount(amount)
                    .currency("KES")
                    .phoneNo(clip(blankToNull(cells.get(PHONE)), 32))
                    .customerName(clip(blankToNull(cells.get(PAID_BY)), 160))
                    .paidAt(paidAt)
                    .rawPayload(toJson(cells))
                    .tenantId(account.getTenantId())
                    .institutionId(account.getInstitutionId())
                    .state(AppConstant.STATEMENT_UNMAPPED)
                    .unmappedReason("Uploaded; not yet matched.")
                    .createdBy(by)
                    .updatedBy(by)
                    .build()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two people uploading the same export at once. The first one's row is the answer.
            CoopStatement raced = statements.findByRefNo(refNo).orElse(null);
            return UploadLine.skipped(line, refNo, raced == null ? "Already recorded." : alreadyRecorded(raced));
        } catch (RuntimeException e) {
            log.warn("Statement upload line {} ({}) could not be stored: {}", line, refNo, e.getMessage());
            return UploadLine.failed(line, refNo, "Could not be stored: " + plain(e));
        }

        Long id = stored.getId();
        try {
            newTransaction.executeWithoutResult(status -> {
                CoopStatement fresh = statements.findById(id).orElseThrow();
                placer.placeAutomatically(fresh, account);
            });
        } catch (RuntimeException e) {
            log.warn("Statement upload line {} ({}) stored but not placed: {}", line, refNo, e.getMessage());
            newTransaction.executeWithoutResult(status -> statements.findById(id).ifPresent(s -> {
                s.setUnmappedReason("Could not be placed automatically: " + plain(e) + " Place it by hand.");
                s.setUpdatedBy(by);
                statements.save(s);
            }));
        }

        CoopStatement after = statements.findById(id).orElseThrow();
        if (after.isMapped()) {
            String bookingRef = after.getMappedBookingId() == null ? null
                    : bookings.findById(after.getMappedBookingId()).map(UnitBooking::getReference).orElse(null);
            return new UploadLine(line, refNo, "PLACED",
                    "Applied to booking " + (bookingRef == null ? "—" : bookingRef) + ".",
                    HashIdUtil.encodeId(id), bookingRef);
        }
        return new UploadLine(line, refNo, "QUEUED",
                after.getUnmappedReason() == null ? "Stored for a person to place." : after.getUnmappedReason(),
                HashIdUtil.encodeId(id), null);
    }

    private String alreadyRecorded(CoopStatement s) {
        if (s.isMapped()) {
            String bookingRef = s.getMappedBookingId() == null ? "—"
                    : bookings.findById(s.getMappedBookingId()).map(UnitBooking::getReference).orElse("—");
            return "Already recorded and applied to booking " + bookingRef + ".";
        }
        if (s.isIgnored()) return "Already recorded and set aside.";
        return "Already recorded; it is in the unused queue.";
    }

    // ── reading the file ──────────────────────────────────────────────────────

    private List<List<String>> read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new HodiException("Choose a CSV file.", HttpStatus.BAD_REQUEST);
        try {
            return CsvRows.parse(new String(file.getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HodiException("The file could not be read.", HttpStatus.BAD_REQUEST);
        }
    }

    /** Which column is where, by our name for it. The two required columns must be present. */
    private static Map<String, Integer> header(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = normalise(header.get(i));
            String key = ALIASES.getOrDefault(name, name);
            if (COLUMNS.stream().anyMatch(c -> c.key().equals(key)) && !columns.containsKey(key)) {
                columns.put(key, i);
            }
        }
        for (UploadColumn column : COLUMNS) {
            if (column.required() && !columns.containsKey(column.key())) {
                throw new HodiException("The file has no \"" + column.key() + "\" column. Download the "
                        + "template for the headings expected.", HttpStatus.BAD_REQUEST);
            }
        }
        return columns;
    }

    private static Map<String, String> cells(List<String> row, Map<String, Integer> columns) {
        Map<String, String> cells = new LinkedHashMap<>();
        columns.forEach((key, index) -> cells.put(key, index < row.size() ? row.get(index) : ""));
        return cells;
    }

    private static String normalise(String heading) {
        return heading == null ? "" : heading.replace("﻿", "").trim().toUpperCase(Locale.ROOT)
                .replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ");
    }

    private static final List<DateTimeFormatter> DATE_TIMES = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT));
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT));

    /**
     * When the money landed.
     *
     * <p>A date with no time is put at midday, so it sorts after anything raised that morning; a date that
     * is still in the future is clamped to now, because a bank's clock must not be able to make a payment
     * refuse. Blank is now.
     */
    static OffsetDateTime paidAt(String value) {
        OffsetDateTime now = OffsetDateTime.now();
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return now;
        ZoneOffset offset = now.getOffset();
        for (DateTimeFormatter f : DATE_TIMES) {
            try {
                OffsetDateTime at = LocalDateTime.parse(text, f).atOffset(offset);
                return at.isAfter(now) ? now : at;
            } catch (DateTimeParseException ignored) { /* next */ }
        }
        for (DateTimeFormatter f : DATES) {
            try {
                OffsetDateTime at = LocalDate.parse(text, f).atTime(LocalTime.NOON).atOffset(offset);
                return at.isAfter(now) ? now : at;
            } catch (DateTimeParseException ignored) { /* next */ }
        }
        throw new DateTimeParseException("Unrecognised date", text, 0);
    }

    private String toJson(Map<String, String> cells) {
        try {
            return mapper.writeValueAsString(cells);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String plain(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName() + ".";
        String first = message.split("\n", 2)[0].trim();
        return first.endsWith(".") ? first : first + ".";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value, int width) {
        return value == null || value.length() <= width ? value : value.substring(0, width);
    }
}
