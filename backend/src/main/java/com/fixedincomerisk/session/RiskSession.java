package com.fixedincomerisk.session;

import com.fixedincomerisk.book.Position;
import com.fixedincomerisk.curve.CurveBootstrapper;
import com.fixedincomerisk.curve.CurveSnapshot;
import com.fixedincomerisk.curve.DiscountCurve;
import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.model.CorrelatedShockGenerator;
import com.fixedincomerisk.model.HullWhiteModel;
import com.fixedincomerisk.repricing.MaterialDependencies;
import com.fixedincomerisk.repricing.RepricingEngine;
import com.fixedincomerisk.risk.CurveSensitivities;
import com.fixedincomerisk.risk.SensitivityCalculator;
import com.fixedincomerisk.session.RiskSnapshot.BookRisk;
import com.fixedincomerisk.session.RiskSnapshot.CtdSwitchEvent;
import com.fixedincomerisk.session.RiskSnapshot.FactorStaleness;
import com.fixedincomerisk.session.RiskSnapshot.LifecycleEvent;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.session.RiskSnapshot.RepricingTelemetry;
import com.fixedincomerisk.session.RiskSnapshot.SessionInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The in-process entry point to the risk engine. Built from a {@link SessionConfig}: loads the curve
 * from the Curve Source, bootstraps it, calibrates Hull-White, and prices the Book.
 *
 * <p>It has two sides that can run on different threads:
 * <ul>
 *   <li>the <b>simulation side</b>, {@link #advanceMarket()}, advances the market one Tick and returns it
 *       as an immutable {@link MarketTick};</li>
 *   <li>the <b>pricing side</b>, {@link #price(MarketTicks)}, reprices the Instruments whose Risk Factors
 *       moved past their Materiality Thresholds since they were last priced, against the newest
 *       Tick only, and returns exactly one Risk Update per cycle. Ticks merged into a cycle are coalesced:
 *       their events are carried, but only the newest market is priced. Dirty Instruments are
 *       priced in parallel on a worker pool when one is configured.</li>
 * </ul>
 * {@link #step()} and {@link #step(int)} run both sides on the calling thread, with no wall clock, so tests
 * are deterministic. Each side must be confined to one thread at a time; {@link #snapshot()} belongs to the
 * pricing side.
 */
public final class RiskSession implements AutoCloseable {

    private final SessionConfig config;
    private final MarketSimulator simulator;
    private final SensitivityCalculator sensitivities;
    private final RepricingEngine repricing;
    private final List<String> ratingBucketLabels;
    /** The Book's distinct Instruments, in Book order. */
    private final List<Instrument> instruments;
    /** Null when repricing runs on the calling thread. */
    private final ExecutorService workers;
    private final Map<String, InstrumentResult> instrumentResults = new HashMap<>();
    /** The latest result for every Position, in Book order. */
    private final Map<String, PositionResult> positions = new LinkedHashMap<>();

    private long sequence;
    private MarketTick priced;
    private BookRisk bookRisk;
    private RepricingTelemetry telemetry;
    private long totalTicksCoalesced;
    private List<LifecycleEvent> recentLifecycleEvents = List.of();
    private List<CtdSwitchEvent> recentCtdSwitches = List.of();

    private RiskSession(SessionConfig config, CurveSnapshot curveSnapshot, HullWhiteModel model) {
        this.config = config;
        this.simulator = new MarketSimulator(config, curveSnapshot, model);
        this.sensitivities = new SensitivityCalculator(config.pillars());
        this.repricing = new RepricingEngine(config.repricing().thresholds());
        this.ratingBucketLabels = config.referenceData().ratingBuckets().stream()
                .map(spec -> spec.bucket().label())
                .toList();
        this.instruments = config.referenceData().book().positions().stream()
                .map(Position::instrument)
                .distinct()
                .toList();
        int threads = config.repricing().workerThreads();
        this.workers = threads == 1 ? null : Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("repricing-worker-", 1).daemon().factory());
        reprice(MarketTicks.of(simulator.opening()));
    }

    public static RiskSession create(SessionConfig config) {
        CurveSnapshot curveSnapshot = config.curveSource().load();
        DiscountCurve initialCurve = CurveBootstrapper.bootstrap(curveSnapshot.curve());
        HullWhiteModel model = HullWhiteModel.calibrate(initialCurve, config.hullWhite());
        return new RiskSession(config, curveSnapshot, model);
    }

    /** Advances exactly one Tick and prices it: a cycle with nothing coalesced. */
    public RiskUpdate step() {
        return price(MarketTicks.of(advanceMarket()));
    }

    /**
     * Advances {@code ticks} Ticks and prices them as one coalesced cycle: only the newest market is
     * priced, and the one Risk Update carries every merged Tick's events.
     */
    public RiskUpdate step(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("A cycle covers at least one Tick");
        }
        MarketTicks batch = MarketTicks.of(advanceMarket());
        for (int i = 1; i < ticks; i++) {
            batch = batch.plus(advanceMarket());
        }
        return price(batch);
    }

    /** Simulation side: false once the configured stop-at-tick has been reached. */
    public boolean canAdvance() {
        return simulator.canAdvance();
    }

    /** Simulation side: advances the market one Tick, without pricing; fails once stopped. */
    public MarketTick advanceMarket() {
        return simulator.advance();
    }

    /** Pricing side: prices the newest of {@code ticks} and returns the cycle's one Risk Update. */
    public RiskUpdate price(MarketTicks ticks) {
        List<PositionResult> repriced = reprice(ticks);
        sequence++;
        MarketTick latest = ticks.latest();
        return new RiskUpdate(sequence, latest.tick(), latest.ticksUntilDayRollover(),
                ticks.dayRollover() ? latest.market().valuationDate().toString() : null,
                repriced, repriced.isEmpty() ? null : bookRisk,
                new RiskUpdate.CurveChange(latest.curve().pillars(), latest.curve().points()),
                ticks.lifecycleEvents(), telemetry, latest.futures(), ticks.ctdSwitches(), latest.credit(),
                ticks.dayRollover() ? latest.swaps() : null);
    }

    /** Pricing side: a complete picture as of the latest priced cycle. */
    public RiskSnapshot snapshot() {
        return new RiskSnapshot(sequence, priced.tick(), priced.ticksUntilDayRollover(), sessionInfo(),
                List.copyOf(positions.values()), bookRisk, priced.curve(), recentLifecycleEvents, telemetry,
                priced.futures(), recentCtdSwitches, priced.credit(), priced.swaps());
    }

    /** The market of the latest priced cycle. */
    public MarketState marketState() {
        return priced.market();
    }

    /**
     * The correlated shocks that drove the latest priced Tick (short rate, Systemic Factor, each futures
     * Basis), or null before the first. A read-only diagnostic.
     */
    public CorrelatedShockGenerator.Shocks lastShocks() {
        return priced.shocks();
    }

    /** The Risk Factors the repricing engine currently treats as the Instrument's dependencies. */
    public Set<RiskFactorId> dependencies(String instrumentId) {
        return repricing.dependencies(instrumentId);
    }

    /** Stops the repricing worker pool, if there is one. */
    @Override
    public void close() {
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    /** Reprices the dirty Instruments against the newest Tick and returns the Positions that changed. */
    private List<PositionResult> reprice(MarketTicks ticks) {
        priced = ticks.latest();
        MarketState market = priced.market();
        List<Instrument> dirty = instruments.stream().filter(i -> repricing.isDirty(i.id(), market)).toList();
        List<Priced> results = priceAll(dirty, market, priced.tick());
        Set<String> repriced = new HashSet<>();
        for (Priced result : results) {
            repricing.setDependencies(result.instrument().id(), result.dependencies());
            repricing.recordPriced(result.instrument().id(), market, priced.tick());
            instrumentResults.put(result.instrument().id(), result.result());
            repriced.add(result.instrument().id());
        }
        List<PositionResult> changed = new ArrayList<>();
        for (Position position : config.referenceData().book().positions()) {
            if (repriced.contains(position.instrument().id())) {
                PositionResult result = positionResult(position, market);
                positions.put(position.positionId(), result);
                changed.add(result);
            }
        }
        if (!changed.isEmpty()) {
            bookRisk = BookRollups.rollUp(List.copyOf(positions.values()), config.pillars(), ratingBucketLabels);
        }
        totalTicksCoalesced += ticks.coalesced();
        telemetry = telemetry(repriced.size(), market, ticks.coalesced());
        recentLifecycleEvents = RiskSnapshot.appendRecent(recentLifecycleEvents, ticks.lifecycleEvents(),
                RiskSnapshot.MAX_RECENT_LIFECYCLE_EVENTS);
        recentCtdSwitches = RiskSnapshot.appendRecent(recentCtdSwitches, ticks.ctdSwitches(),
                RiskSnapshot.MAX_RECENT_CTD_SWITCHES);
        return List.copyOf(changed);
    }

    /**
     * Prices each dirty Instrument, on the worker pool if there is one. Pricing reads only the immutable
     * market and each Instrument's own terms, so the results are the same either way; they are returned in
     * Book order and applied on the calling thread.
     */
    private List<Priced> priceAll(List<Instrument> dirty, MarketState market, long tick) {
        if (workers == null || dirty.size() < 2) {
            return dirty.stream().map(instrument -> price(instrument, market, tick)).toList();
        }
        List<Future<Priced>> futures = dirty.stream()
                .map(instrument -> workers.submit(() -> price(instrument, market, tick)))
                .toList();
        List<Priced> results = new ArrayList<>(futures.size());
        try {
            for (Future<Priced> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while repricing", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Repricing failed", e.getCause());
        }
        return results;
    }

    /** Prices one Instrument and its sensitivities, and works out its material dependencies. Pure. */
    private Priced price(Instrument instrument, MarketState market, long tick) {
        double dirty = instrument.dirtyValue(market);
        double accrued = instrument.accruedInterest(market.valuationDate());
        CurveSensitivities perUnit = sensitivities.curveSensitivities(instrument, market);
        double cs01 = sensitivities.cs01(instrument, market);
        Set<RiskFactorId> dependencies = MaterialDependencies.of(instrument.riskFactors(market, config.pillars()),
                config.pillars(), perUnit, config.repricing().minPillarExposure());
        return new Priced(instrument, dependencies, new InstrumentResult(dirty, accrued, perUnit, cs01, tick));
    }

    private PositionResult positionResult(Position position, MarketState market) {
        Instrument instrument = position.instrument();
        InstrumentResult result = instrumentResults.get(instrument.id());
        CurveSensitivities risk = result.perUnit().scaledBy(position.quantity());
        return new PositionResult(
                position.positionId(),
                instrument.id(),
                instrument.type().name(),
                instrument.description(),
                position.quantity(),
                (result.dirty() - result.accrued()) * 100,
                result.accrued() * 100,
                result.dirty() * 100,
                instrument.marginedDaily() ? 0 : result.dirty() * position.quantity(),
                risk.dv01(),
                BookRollups.bucketDv01s(config.pillars(), risk.bucketedDv01()),
                result.cs01() * position.quantity() + 0.0,
                instrument.issuer().map(issuer -> market.credit().rating(issuer)).orElse(null),
                result.tick());
    }

    private RepricingTelemetry telemetry(int instrumentsRepriced, MarketState market, int ticksCoalesced) {
        List<FactorStaleness> staleness = new ArrayList<>();
        repricing.maxStaleness(market).forEach((type, max) -> {
            // A discrete factor (Valuation Date, Proxy Bond, rating) reprices on any change, so it is never stale.
            if (!type.anyChangeIsMove()) {
                staleness.add(new FactorStaleness(type.name(), type.unit(), max,
                        config.repricing().thresholds().threshold(type)));
            }
        });
        return new RepricingTelemetry(instrumentsRepriced, instruments.size(), staleness, ticksCoalesced,
                totalTicksCoalesced);
    }

    private SessionInfo sessionInfo() {
        CurveSnapshot curveSnapshot = simulator.curveSnapshot();
        return new SessionInfo(
                curveSnapshot.source().name(),
                curveSnapshot.curve().curveDate().toString(),
                priced.market().valuationDate().toString(),
                config.simulation().seed(),
                config.simulation().simulatedTimePerTick().toSeconds(),
                config.simulation().ticksPerDay(),
                config.simulation().stopAtTick() == 0 ? null : config.simulation().stopAtTick());
    }

    /** One Instrument's per-unit price and risk, as of the Tick it was last priced. */
    private record InstrumentResult(double dirty, double accrued, CurveSensitivities perUnit, double cs01, long tick) {
    }

    /** The outcome of pricing one Instrument, before it is applied to the session's state. */
    private record Priced(Instrument instrument, Set<RiskFactorId> dependencies, InstrumentResult result) {
    }
}
