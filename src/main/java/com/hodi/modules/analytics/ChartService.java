package com.hodi.modules.analytics;

import com.hodi.modules.analytics.ChartCatalogue.Chart;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws one chart from the catalogue.
 *
 * <h2>Nothing here comes from a request except a key</h2>
 *
 * <p>The view name and the scope columns are literals from {@link ChartCatalogue}; the only thing a caller
 * supplies is the key, and a key that names nothing is a 404. That is the same rule the report paths follow
 * and for the same reason: a view name cannot be a bind parameter, so anything spliced into this SQL has to
 * come from a file rather than from a caller.
 *
 * <h2>Every query is scoped, and a chart that cannot be is refused</h2>
 *
 * <p>{@code TenantScope.sqlPredicate} is spliced onto the columns the chart declares. An aggregate that
 * escaped scoping would be one organisation reading another's totals — and a total does not look like
 * somebody else's data until you work out what it is made of, which is what makes it worse than a row leak.
 *
 * <h2>The summary is composed here, not in the browser</h2>
 *
 * <p>One sentence saying what the chart shows, sent with it. It is the visible caption, the chart's accessible
 * name, and the caption of the data table somebody can toggle instead of reading the picture. Composing it on
 * the server means the sentence and the numbers cannot drift, and it means a chart is never a picture with no
 * text alternative — the payload is already a table, so the alternative costs nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartService {

    private final JdbcTemplate jdbc;

    /**
     * @param key      the catalogue key, echoed so a client can match a response to a request
     * @param kind     LINE, BAR, STACKED_BAR or DONUT
     * @param labels   the x axis, or the slice names on a donut
     * @param series   one entry per line, stack or slice
     * @param summary  a sentence describing the whole chart. Caption, accessible name, and table caption
     * @param empty    whether there is nothing to draw, so a client shows a reason rather than empty axes
     */
    public record ChartData(
            String key,
            String title,
            String description,
            String kind,
            List<String> labels,
            List<Series> series,
            String valueLabel,
            boolean money,
            String summary,
            boolean empty) {}

    /** One line, stack or slice. Values line up with {@code labels} by position. */
    public record Series(String name, List<BigDecimal> values, BigDecimal total) {}

    /** What a client may draw at all, given who is asking. */
    @Transactional(readOnly = true)
    public List<Map<String, String>> available() {
        return ChartCatalogue.all().stream()
                .filter(this::maySee)
                .map(c -> Map.of(
                        "key", c.key(),
                        "title", c.title(),
                        "description", c.description(),
                        "kind", c.kind().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChartData draw(String key) {
        Chart chart = ChartCatalogue.byKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("Chart", key));
        if (!maySee(chart)) {
            // Not found rather than forbidden: which charts exist is itself something to keep quiet about.
            throw new ResourceNotFoundException("Chart", key);
        }

        String sql = "select bucket, series, sum(value) as value from " + chart.view()
                + " where " + scope(chart) + " group by 1, 2 order by 1, 2";

        List<Row> rows = jdbc.query(sql, (rs, i) -> new Row(
                rs.getDate("bucket") == null ? null : rs.getDate("bucket").toLocalDate(),
                rs.getString("series"),
                rs.getBigDecimal("value") == null ? BigDecimal.ZERO : rs.getBigDecimal("value")));

        return shape(chart, rows);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private record Row(LocalDate bucket, String series, BigDecimal value) {}

    /**
     * The scope predicate, across every column the chart declares.
     *
     * <p>ORed, because the columns are alternatives rather than conditions: a development belongs to a tenant
     * or to an institution, and requiring both would return nothing at all. A chart that declares no scope
     * column never reaches here — the catalogue's own contract is that the list is never empty, and this
     * refuses rather than silently returning everything if that is ever broken.
     */
    private String scope(Chart chart) {
        if (chart.scopeColumns().isEmpty()) {
            throw new IllegalStateException(
                    "Chart " + chart.key() + " declares no scope column; refusing to run it unscoped");
        }
        List<String> parts = new ArrayList<>();
        for (String column : chart.scopeColumns()) {
            String predicate = TenantScope.sqlPredicate(column);
            if ("TRUE".equals(predicate)) return "TRUE";      // unrestricted: platform staff
            if ("FALSE".equals(predicate)) continue;          // this axis grants nothing
            parts.add("(" + predicate + ")");
        }
        return parts.isEmpty() ? "FALSE" : String.join(" OR ", parts);
    }

    /**
     * Whether the caller holds the permission the chart is behind.
     *
     * <p>Read off the granted authorities, which is where a permission lands on the principal — the same
     * strings {@code hasAuthority(...)} matches on the controller. Checked here as well as there because
     * {@link #available()} has to filter the list, and a chart somebody may not draw should not be offered.
     */
    private boolean maySee(Chart chart) {
        return AuthContext.current()
                .map(caller -> caller.getAuthorities().stream()
                        .anyMatch(a -> chart.permission().name().equals(a.getAuthority())))
                .orElse(false);
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /**
     * Turns rows into labelled series.
     *
     * <p>A time chart's labels are its months and its series are the categories; a composition chart has no
     * time at all, so its labels *are* the categories and there is one series. Both come out of the same view
     * shape, which is why the view always has a {@code bucket} even when it is null.
     */
    private ChartData shape(Chart chart, List<Row> rows) {
        if (rows.isEmpty()) {
            return new ChartData(chart.key(), chart.title(), chart.description(), chart.kind().name(),
                    List.of(), List.of(), chart.valueLabel(), chart.money(),
                    "Nothing to show yet for " + chart.title().toLowerCase() + ".", true);
        }

        boolean overTime = rows.stream().anyMatch(r -> r.bucket() != null);

        if (!overTime) {
            // One series, one value per category. The labels are the categories themselves.
            List<String> labels = rows.stream().map(r -> pretty(r.series())).toList();
            List<BigDecimal> values = rows.stream().map(Row::value).toList();
            BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ChartData(chart.key(), chart.title(), chart.description(), chart.kind().name(),
                    labels, List.of(new Series(chart.title(), values, total)),
                    chart.valueLabel(), chart.money(),
                    compositionSummary(chart, labels, values, total), false);
        }

        List<LocalDate> buckets = rows.stream().map(Row::bucket).filter(java.util.Objects::nonNull)
                .distinct().sorted().toList();
        LinkedHashSet<String> names = rows.stream().map(r -> pretty(r.series()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, Map<LocalDate, BigDecimal>> byName = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.bucket() == null) continue;
            byName.computeIfAbsent(pretty(row.series()), n -> new LinkedHashMap<>())
                    .merge(row.bucket(), row.value(), BigDecimal::add);
        }

        List<Series> series = new ArrayList<>();
        for (String name : names) {
            Map<LocalDate, BigDecimal> points = byName.getOrDefault(name, Map.of());
            // Zero-filled across every bucket, so a line does not jump a gap it should cross flat.
            List<BigDecimal> values = buckets.stream()
                    .map(b -> points.getOrDefault(b, BigDecimal.ZERO)).toList();
            series.add(new Series(name, values,
                    values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        }

        List<String> labels = buckets.stream().map(MONTH::format).toList();
        return new ChartData(chart.key(), chart.title(), chart.description(), chart.kind().name(),
                labels, series, chart.valueLabel(), chart.money(),
                trendSummary(chart, labels, series), false);
    }

    /**
     * A sentence about a composition: the total and what dominates it.
     *
     * <p>Deliberately says the largest slice and its share, because that is what somebody takes from a donut
     * and it is the thing a colour-blind reader cannot get from the picture.
     */
    private String compositionSummary(Chart chart, List<String> labels, List<BigDecimal> values,
                                      BigDecimal total) {
        int top = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i).compareTo(values.get(top)) > 0) top = i;
        }
        String share = total.compareTo(BigDecimal.ZERO) > 0
                ? values.get(top).multiply(BigDecimal.valueOf(100))
                        .divide(total, 0, RoundingMode.HALF_UP) + "%"
                : "all";
        return "%s across %d categories: %s, the largest, at %s.".formatted(
                amount(chart, total), labels.size(), labels.get(top), share);
    }

    /**
     * A sentence about a trend: the span, the total, and which way the last month moved.
     *
     * <p>"Up" and "down" rather than a slope, because a caption is read aloud and a gradient is not a word.
     */
    private String trendSummary(Chart chart, List<String> labels, List<Series> series) {
        BigDecimal total = series.stream().map(Series::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        String span = labels.size() == 1
                ? labels.getFirst()
                : labels.getFirst() + " to " + labels.getLast();

        String movement = "";
        if (labels.size() >= 2) {
            BigDecimal last = sumAt(series, labels.size() - 1);
            BigDecimal previous = sumAt(series, labels.size() - 2);
            int direction = last.compareTo(previous);
            movement = direction > 0 ? " Up on the month before."
                    : direction < 0 ? " Down on the month before."
                    : " Level with the month before.";
        }
        return "%s from %s, across %d %s.%s".formatted(
                amount(chart, total), span, series.size(),
                series.size() == 1 ? "series" : "series", movement);
    }

    private BigDecimal sumAt(List<Series> series, int index) {
        return series.stream()
                .map(s -> index < s.values().size() ? s.values().get(index) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** "1,240 listings" or "KES 4,300,000" — the units matter in a sentence read on its own. */
    private String amount(Chart chart, BigDecimal value) {
        String number = String.format(Locale.ENGLISH, "%,.0f", value);
        return chart.money() ? chart.valueLabel() + " " + number : number + " " + chart.valueLabel();
    }

    /** ENUM_VALUES as words. A caption saying "UNDER_CONSTRUCTION" is a column name read aloud. */
    private String pretty(String code) {
        if (code == null || code.isBlank()) return "Unknown";
        String word = code.toLowerCase(Locale.ENGLISH).replace('_', ' ');
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
