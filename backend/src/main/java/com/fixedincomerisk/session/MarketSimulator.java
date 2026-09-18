package com.fixedincomerisk.session;

import com.fixedincomerisk.book.Position;
import com.fixedincomerisk.credit.CreditFactorSimulator;
import com.fixedincomerisk.credit.CreditMarker;
import com.fixedincomerisk.credit.CreditObservables;
import com.fixedincomerisk.credit.CreditObservationSimulator;
import com.fixedincomerisk.credit.Issuer;
import com.fixedincomerisk.credit.RatingBucketSpec;
import com.fixedincomerisk.curve.CurveSnapshot;
import com.fixedincomerisk.instrument.CashFlow;
import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.instrument.InterestRateSwap;
import com.fixedincomerisk.instrument.ProxyBond;
import com.fixedincomerisk.instrument.TreasuryFuture;
import com.fixedincomerisk.market.FixingHistory;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.model.CorrelatedShockGenerator;
import com.fixedincomerisk.model.FuturesBasisSimulator;
import com.fixedincomerisk.model.FuturesBasisSimulator.CtdSwitch;
import com.fixedincomerisk.model.HullWhiteModel;
import com.fixedincomerisk.model.HullWhiteSimulator;
import com.fixedincomerisk.session.RiskSnapshot.CreditView;
import com.fixedincomerisk.session.RiskSnapshot.CtdSwitchEvent;
import com.fixedincomerisk.session.RiskSnapshot.CurvePoint;
import com.fixedincomerisk.session.RiskSnapshot.CurveView;
import com.fixedincomerisk.session.RiskSnapshot.FuturesView;
import com.fixedincomerisk.session.RiskSnapshot.IssuerView;
import com.fixedincomerisk.session.RiskSnapshot.LifecycleEvent;
import com.fixedincomerisk.session.RiskSnapshot.ParInput;
import com.fixedincomerisk.session.RiskSnapshot.SectorLevel;
import com.fixedincomerisk.session.RiskSnapshot.SwapView;
import com.fixedincomerisk.simulation.SimulationClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The simulation side of a session: owns the single seeded random source, the clock and every simulator,
 * and advances the market one Tick at a time, publishing each as an immutable {@link MarketTick}. It never
 * prices anything, so it can run on its own thread and keep ticking however long repricing takes.
 * Credit's latent state stays inside the credit simulators; only observables, public ratings
 * and Marks reach a {@code MarketTick}.
 *
 * <p>Confined to one thread at a time.
 */
final class MarketSimulator {

    /** A fixed algorithm, so a seed replays identically across JDK builds. */
    private static final String RANDOM_ALGORITHM = "L64X128MixRandom";
    private static final double CHART_STEP_YEARS = 0.25;
    private static final double CHART_MAX_YEARS = 30;

    private final SessionConfig config;
    private final CurveSnapshot curveSnapshot;
    private final RandomGenerator random;
    private final SimulationClock clock;
    private final HullWhiteSimulator shortRate;
    private final CorrelatedShockGenerator shocks;
    private final FuturesBasisSimulator futuresBasis;
    /** The Book's futures contracts, by contract id. */
    private final Map<String, TreasuryFuture> futures = new LinkedHashMap<>();
    private final Map<String, Integer> ctdSwitchCounts = new HashMap<>();
    private final Map<String, Long> lastCtdSwitchTicks = new HashMap<>();
    private final CreditFactorSimulator creditFactors;
    private final CreditObservationSimulator creditObservations;
    private final CreditMarker marker;
    /** The Book's swaps, and the Fixings of their floating index. */
    private final List<InterestRateSwap> swaps;
    private final FixingHistory fixingHistory = new FixingHistory();

    private MarketState market;

    MarketSimulator(SessionConfig config, CurveSnapshot curveSnapshot, HullWhiteModel model) {
        this.config = config;
        this.curveSnapshot = curveSnapshot;
        this.random = RandomGeneratorFactory.of(RANDOM_ALGORITHM).create(config.simulation().seed());
        this.clock = new SimulationClock(config.simulation(), curveSnapshot.curve().curveDate());
        this.shortRate = new HullWhiteSimulator(model);
        this.shocks = new CorrelatedShockGenerator(config.correlations());
        Map<String, Integer> deliverableCounts = new LinkedHashMap<>();
        for (TreasuryFuture future : config.referenceData().futuresInBook()) {
            futures.put(future.id(), future);
            deliverableCounts.put(future.id(), future.deliverables().size());
        }
        this.futuresBasis = new FuturesBasisSimulator(config.futuresBasis(), deliverableCounts);
        List<Issuer> issuers = config.referenceData().issuers();
        this.creditFactors = new CreditFactorSimulator(config.credit(), config.creditEvents(),
                config.referenceData().ratingBuckets(), issuers);
        this.creditObservations = new CreditObservationSimulator(creditFactors, config.creditEvents(), issuers);
        this.marker = new CreditMarker(creditFactors.observables(), issuers);
        this.swaps = config.referenceData().book().positions().stream()
                .map(Position::instrument)
                .distinct()
                .filter(InterestRateSwap.class::isInstance)
                .map(InterestRateSwap.class::cast)
                .toList();
        seedFixings();
        this.market = currentMarket();
    }

    /** The market at session start, before any Tick. */
    MarketTick opening() {
        return tick(false, List.of(), List.of(), null);
    }

    /**
     * Advances exactly one Tick. On a Day Rollover the Valuation Date moves, the cash flows crossed are
     * processed, and reset dates record their Fixings. A Rating Migration is public at once through the
     * ratings in the new market.
     */
    MarketTick advance() {
        if (!canAdvance()) {
            throw new IllegalStateException("The simulation is stopped at tick " + clock.tick());
        }
        SimulationClock.Step step = clock.advance();
        CorrelatedShockGenerator.Shocks tickShocks = shocks.next(random, futures.size());
        shortRate.advance(step.dt(), tickShocks.shortRate());
        List<CtdSwitchEvent> switches = ctdSwitchEvents(futuresBasis.advance(step.dt(), tickShocks.basis(), random));
        creditFactors.advance(clock.tick(), step.dt(), tickShocks.systemic(), random);
        marker.update(clock.tick(), creditFactors.observables(), creditObservations.advance(step.dt(), random));
        List<LifecycleEvent> events = step.isDayRollover()
                ? lifecycleEvents(step.previousValuationDate(), step.valuationDate())
                : List.of();
        if (step.isDayRollover()) {
            recordFixings();
        }
        market = currentMarket();
        return tick(step.isDayRollover(), events, switches, tickShocks);
    }

    /** False once the configured stop-at-tick has been reached. */
    boolean canAdvance() {
        return config.simulation().canAdvancePast(clock.tick());
    }

    CurveSnapshot curveSnapshot() {
        return curveSnapshot;
    }

    private MarketTick tick(boolean dayRollover, List<LifecycleEvent> events, List<CtdSwitchEvent> switches,
                            CorrelatedShockGenerator.Shocks tickShocks) {
        return new MarketTick(clock.tick(), clock.ticksUntilDayRollover(), market, dayRollover, curveView(),
                futuresViews(), creditView(), swapViews(), events, switches, tickShocks);
    }

    private MarketState currentMarket() {
        return new MarketState(clock.valuationDate(), shortRate.curve(), futuresBasis.state(), creditMarket(),
                fixingHistory.fixings());
    }

    /** Cash flows each Position receives (or, when short, pays) for dates in (from, to]. */
    private List<LifecycleEvent> lifecycleEvents(LocalDate from, LocalDate to) {
        List<LifecycleEvent> events = new ArrayList<>();
        for (Position position : config.referenceData().book().positions()) {
            Instrument instrument = position.instrument();
            for (CashFlow cashFlow : instrument.cashFlowsPaid(market, from, to)) {
                events.add(new LifecycleEvent(
                        clock.tick(),
                        cashFlow.date().toString(),
                        position.positionId(),
                        instrument.id(),
                        instrument.description(),
                        cashFlow.kind().name(),
                        cashFlow.amount() * 100,
                        cashFlow.amount() * position.quantity()));
            }
        }
        return events;
    }

    private List<CtdSwitchEvent> ctdSwitchEvents(List<CtdSwitch> switches) {
        List<CtdSwitchEvent> events = new ArrayList<>();
        for (CtdSwitch ctdSwitch : switches) {
            TreasuryFuture future = futures.get(ctdSwitch.contract());
            ctdSwitchCounts.merge(future.id(), 1, Integer::sum);
            lastCtdSwitchTicks.put(future.id(), clock.tick());
            events.add(new CtdSwitchEvent(clock.tick(), future.id(),
                    future.deliverables().get(ctdSwitch.fromIndex()).bond().id(),
                    future.deliverables().get(ctdSwitch.toIndex()).bond().id(),
                    ctdSwitch.basisJump()));
        }
        return events;
    }

    /**
     * Seeds the Fixing of every floating period already running at startup (including one resetting
     * today) from the t=0 curve.
     */
    private void seedFixings() {
        MarketState opening = new MarketState(clock.valuationDate(), shortRate.curve());
        for (InterestRateSwap swap : swaps) {
            swap.currentFloatingPeriod(opening.valuationDate()).ifPresent(period ->
                    fixingHistory.record(period.start(), FixingHistory.indexRate(opening, period.start())));
        }
    }

    /**
     * On a Day Rollover onto a reset date, records that date's Fixing from the simulated curve as it is
     * now. A date that already has a Fixing keeps it.
     */
    private void recordFixings() {
        MarketState today = new MarketState(clock.valuationDate(), shortRate.curve());
        for (InterestRateSwap swap : swaps) {
            if (swap.resetDates().contains(today.valuationDate())) {
                fixingHistory.record(today.valuationDate(), FixingHistory.indexRate(today, today.valuationDate()));
            }
        }
    }

    /** The credit market as the risk engine may see it: observable factors, public ratings and Marks. */
    private MarketState.CreditMarket creditMarket() {
        CreditObservables observables = creditFactors.observables();
        Map<String, Double> sectors = new HashMap<>();
        observables.sectors().forEach((bucket, level) -> sectors.put(bucket.label(), level));
        Map<String, String> ratings = new HashMap<>();
        observables.ratings().forEach((issuer, bucket) -> ratings.put(issuer, bucket.label()));
        return new MarketState.CreditMarket(observables.systemic(), sectors, ratings, marker.marks());
    }

    private CurveView curveView() {
        List<CurvePoint> pillars = config.pillars().stream()
                .map(p -> new CurvePoint(p.label(), p.years(), market.curve().zeroRate(p.years())))
                .toList();
        List<CurvePoint> points = new ArrayList<>();
        for (int i = 1; i * CHART_STEP_YEARS <= CHART_MAX_YEARS + 1e-9; i++) {
            double years = i * CHART_STEP_YEARS;
            points.add(new CurvePoint(null, years, market.curve().zeroRate(years)));
        }
        List<ParInput> parInputs = curveSnapshot.curve().points().stream()
                .map(p -> new ParInput(p.tenor(), p.years(), p.parYield()))
                .toList();
        return new CurveView(pillars, List.copyOf(points), parInputs);
    }

    private List<FuturesView> futuresViews() {
        List<FuturesView> views = new ArrayList<>();
        for (TreasuryFuture future : futures.values()) {
            MarketState.FuturesMarket state = market.futures(future.id());
            ProxyBond proxy = future.currentProxy(state);
            views.add(new FuturesView(future.id(), future.description(), proxy.bond().id(), proxy.bond().description(),
                    proxy.conversionFactor(), state.basis(), ctdSwitchCounts.getOrDefault(future.id(), 0),
                    lastCtdSwitchTicks.get(future.id())));
        }
        return views;
    }

    private CreditView creditView() {
        CreditObservables observables = creditFactors.observables();
        List<SectorLevel> sectors = config.referenceData().ratingBuckets().stream()
                .map(RatingBucketSpec::bucket)
                .map(bucket -> new SectorLevel(bucket.label(), observables.sector(bucket) * 1e4))
                .toList();
        List<IssuerView> issuers = new ArrayList<>();
        for (Issuer issuer : config.referenceData().issuers()) {
            Optional<CreditMarker.Observed> lastPrint = marker.lastPrint(issuer.id());
            Optional<CreditMarker.Observed> lastQuote = marker.lastQuote(issuer.id());
            Optional<CreditMarker.Migration> migration = marker.lastMigration(issuer.id());
            issuers.add(new IssuerView(issuer.id(), issuer.name(), observables.rating(issuer.id()).label(),
                    marker.marks().get(issuer.id()) * 1e4,
                    lastPrint.map(o -> o.spread() * 1e4).orElse(null),
                    lastPrint.map(CreditMarker.Observed::tick).orElse(null),
                    lastQuote.map(o -> o.spread() * 1e4).orElse(null),
                    lastQuote.map(CreditMarker.Observed::tick).orElse(null),
                    creditObservations.printIntensity(issuer.id()),
                    migration.map(m -> m.from().label()).orElse(null),
                    migration.map(CreditMarker.Migration::tick).orElse(null),
                    migration.map(CreditMarker.Migration::markStale).orElse(false)));
        }
        return new CreditView(observables.systemic() * 1e4, sectors, issuers);
    }

    private List<SwapView> swapViews() {
        List<SwapView> views = new ArrayList<>();
        for (InterestRateSwap swap : swaps) {
            Optional<InterestRateSwap.Period> current = swap.currentFloatingPeriod(market.valuationDate());
            String nextReset = swap.resetDates().stream()
                    .filter(date -> date.isAfter(market.valuationDate()))
                    .findFirst().map(LocalDate::toString).orElse(null);
            views.add(new SwapView(swap.id(), swap.description(), swap.direction().name(), swap.fixedRate(),
                    current.map(p -> p.start().toString()).orElse(null),
                    current.map(p -> p.end().toString()).orElse(null),
                    current.map(p -> market.fixings().rate(p.start())).orElse(null),
                    nextReset));
        }
        return views;
    }
}
