package com.hodi.modules.analytics;

import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.analytics.AnalyticsViews.*;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentVisibility;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Analytics: a window, its shape, and what it is made of.
 *
 * <p>Six reads rather than one. The page loads them in parallel, so a panel with a slow query delays itself
 * instead of the whole screen, and a tab nobody opens costs nothing to draw.
 *
 * <p>They share one thing and it matters: {@link AnalyticsWindow}. The dashboard's cards each own their
 * period — <i>overall</i>, <i>a month</i> and <i>a year</i> are different questions. Here every panel answers
 * the <b>same</b> question about the same stretch of time, because the point of the page is reading them
 * against each other.
 *
 * <p>Nothing here is stored, generated overnight or cached. Every figure is a sum over the bookings, payments,
 * cost lines, drawdowns and units themselves, so the page cannot disagree with the lists behind it and there
 * is no refresh button to press.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /**
     * How many developments the comparison table carries. Enough for any real portfolio, and the view says
     * when it is hit; beyond it the table is a report, and the report centre downloads one.
     */
    private static final int DEVELOPMENT_CAP = 500;

    /** How many developments the movers list names, up and down together. */
    private static final int MOVERS = 8;

    /** How many owing bookings the chase list names. */
    private static final int WORST = 10;

    private final AnalyticsQueries queries;
    private final AnalyticsFlowQueries flow;
    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;

    /**
     * The KPI strip: the window, and the window before it.
     *
     * <p>The comparison is the whole reason this endpoint is not the dashboard. Two identical queries over two
     * adjacent windows of equal length — equal length being the point, since a six-month window judged against
     * a twelve-month one would report a collapse in sales that is really just half as many months.
     */
    @Transactional(readOnly = true)
    public SummaryView summary(AnalyticsWindow window, String developmentHash) {
        Long developmentId = development(developmentHash);
        AnalyticsWindow previous = window.previous();
        MoneyTotals now = queries.totals(window, developmentId);
        MoneyTotals then = queries.totals(previous, developmentId);
        return new SummaryView(window, previous,
                Figure.of(now.contracted(), then.contracted()),
                Figure.of(now.collected(), then.collected()),
                Figure.of(now.spent(), then.spent()),
                Figure.of(now.drawn(), then.drawn()),
                Figure.of(now.net(), then.net()),
                now.bookings(), now.payments(), now.unitsSold(),
                queries.positions(developmentId),
                queries.trend(window, developmentId));
    }

    /**
     * How well what is due gets paid: due against collected, how promptly, by what channel, and how the
     * phone prompts fared.
     */
    @Transactional(readOnly = true)
    public CollectionsView collections(AnalyticsWindow window, String developmentHash) {
        Long developmentId = development(developmentHash);
        List<DuePoint> months = flow.dueAndCollected(window, developmentId);
        BigDecimal due = months.stream().map(DuePoint::due).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = months.stream().map(DuePoint::collected).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PromptPoint> prompts = flow.promptsByMonth(window, developmentId);
        return new CollectionsView(window, due, collected, flow.lateness(window, developmentId), months,
                flow.channelsByMonth(window, developmentId), prompts,
                prompts.stream().mapToInt(PromptPoint::sent).sum(), prompts.stream().mapToInt(PromptPoint::paid).sum(),
                prompts.stream().mapToInt(PromptPoint::failed).sum(), prompts.stream().mapToInt(PromptPoint::unanswered).sum());
    }

    /** How many enquiries become sales in the window, how many fall out at each step, and how long each took. */
    @Transactional(readOnly = true)
    public FunnelView funnel(AnalyticsWindow window) {
        int[] n = flow.stageCounts(window);
        String[] keys = {"enquiries", "viewings", "offers", "bookings", "completed"};
        String[] labels = {"Enquiries", "Viewings", "Offers", "Bookings", "Completed"};
        List<Stage> stages = new ArrayList<>();
        for (int i = 0; i < n.length; i++) {
            BigDecimal conversion = i == 0 || n[i - 1] == 0 ? null
                    : BigDecimal.valueOf(n[i]).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(n[i - 1]), 1, RoundingMode.HALF_UP);
            stages.add(new Stage(keys[i], labels[i], n[i], conversion));
        }
        return new FunnelView(window, stages, flow.intervals(window), flow.offersByOutcome(window),
                flow.offersConverted(window), flow.viewingsByOutcome(window));
    }

    @Transactional(readOnly = true)
    public TrendView trend(AnalyticsWindow window, String developmentHash) {
        Long developmentId = development(developmentHash);
        return new TrendView(window, queries.trend(window, developmentId));
    }

    /** Three cuts: what came in by payment type, what went out by cost category, what is left by unit state. */
    @Transactional(readOnly = true)
    public CompositionView composition(AnalyticsWindow window, String developmentHash) {
        Long developmentId = development(developmentHash);
        return new CompositionView(window,
                queries.collectionsByType(window, developmentId),
                queries.spendByCategory(window, developmentId),
                queries.unitsByState(developmentId));
    }

    /**
     * Debt by age, and the ten largest balances.
     *
     * <p>The bands and the worklist are one endpoint because they are one decision: the bands say how bad it
     * is, the list says whose door to knock on. Receivable and overdue are today's position, so no window.
     */
    @Transactional(readOnly = true)
    public ReceivablesView receivables(String developmentHash) {
        Long developmentId = development(developmentHash);
        Positions now = queries.positions(developmentId);
        return new ReceivablesView(now.receivable(), now.overdue(), now.liveBookings(), now.overdueBookings(),
                queries.ageing(developmentId), queries.worstBookings(developmentId, WORST));
    }

    /** Every development in scope, one row each, so the portfolio can be ranked — and who moved. */
    @Transactional(readOnly = true)
    public DevelopmentsView developments(AnalyticsWindow window, String developmentHash) {
        Long developmentId = development(developmentHash);
        List<DevelopmentComparison> rows = queries.developments(window, developmentId, DEVELOPMENT_CAP);
        int available = queries.countDevelopments(developmentId);

        int units = 0;
        int sold = 0;
        BigDecimal contracted = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal receivable = BigDecimal.ZERO;
        BigDecimal budget = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal drawn = BigDecimal.ZERO;
        for (DevelopmentComparison row : rows) {
            units += row.unitsTotal();
            sold += row.unitsSold();
            contracted = contracted.add(row.contracted());
            collected = collected.add(row.collected());
            receivable = receivable.add(row.receivable());
            if (row.budget() != null) budget = budget.add(row.budget());
            spent = spent.add(row.spent());
            drawn = drawn.add(row.drawn());
        }

        /*
         * The same question of the window before, so the table can say who moved.
         *
         * A ranking by collections tells somebody which project is biggest, which they knew. The useful ranking
         * is by change, and that needs the previous window's rows — one more query of the same shape.
         */
        Map<String, BigDecimal> before = new HashMap<>();
        for (DevelopmentComparison row : queries.developments(window.previous(), developmentId, DEVELOPMENT_CAP)) {
            before.put(row.id(), row.collectedInWindow());
        }
        List<Mover> movers = new ArrayList<>();
        for (DevelopmentComparison row : rows) {
            BigDecimal previous = before.getOrDefault(row.id(), BigDecimal.ZERO);
            if (row.collectedInWindow().signum() == 0 && previous.signum() == 0) continue;
            movers.add(new Mover(row.id(), row.name(), row.collectedInWindow(), previous));
        }
        // Biggest movement either way: the project that fell hardest belongs beside the one that rose most.
        movers.sort((a, b) -> b.delta().abs().compareTo(a.delta().abs()));
        List<Mover> top = movers.size() > MOVERS ? movers.subList(0, MOVERS) : movers;

        return new DevelopmentsView(window, rows, available > rows.size(), units, sold, contracted, collected,
                receivable, budget, spent, drawn, List.copyOf(top));
    }

    /** Enquiries, viewings and offers over the same window as the money. */
    @Transactional(readOnly = true)
    public PipelineView pipeline(AnalyticsWindow window) {
        return new PipelineView(window, queries.pipeline(window));
    }

    /**
     * The development a request narrows to, if the caller may see it.
     *
     * <p>Not found rather than forbidden, like everywhere else a development is addressed: which projects exist
     * is itself something to keep quiet about. Null narrows to nothing, which is the whole scope.
     */
    public Long development(String hash) {
        Long id = HashIdUtil.decodeId(hash);
        if (id == null) return null;
        Development development = developments.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Development", hash));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", hash);
        }
        return id;
    }
}
