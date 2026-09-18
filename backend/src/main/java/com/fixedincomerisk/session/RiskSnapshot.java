package com.fixedincomerisk.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete picture of every Position, the Book rollups, the curve and the recent lifecycle events,
 * sent when a client connects.
 * Prices are quoted per 100 of face; values are in currency units. DV01 and CS01 are in currency units
 * per basis point, positive when the value rises as rates or spreads fall; risk is on dirty value.
 * Spreads are in basis points.
 *
 * @param sequence               the sequence number of the last Risk Update this snapshot includes
 * @param tick                   the Tick the snapshot was priced against
 * @param ticksUntilDayRollover  Ticks still to go before the next Day Rollover
 * @param recentLifecycleEvents  the latest lifecycle events, oldest first, at most
 *                               {@link #MAX_RECENT_LIFECYCLE_EVENTS}
 * @param telemetry              what the latest cycle repriced, and how stale the rest is
 * @param futures                each Treasury futures contract's market state: current Proxy Bond and Basis
 * @param recentCtdSwitches      the latest CTD Switches, oldest first, at most
 *                               {@link #MAX_RECENT_CTD_SWITCHES}
 * @param credit                 the observable credit market and each issuer's Mark and observations
 * @param swaps                  each interest rate swap's current floating period and its Fixing
 */
public record RiskSnapshot(
        long sequence,
        long tick,
        int ticksUntilDayRollover,
        SessionInfo session,
        List<PositionResult> positions,
        BookRisk bookRisk,
        CurveView curve,
        List<LifecycleEvent> recentLifecycleEvents,
        RepricingTelemetry telemetry,
        List<FuturesView> futures,
        List<CtdSwitchEvent> recentCtdSwitches,
        CreditView credit,
        List<SwapView> swaps) {

    public static final int MAX_RECENT_LIFECYCLE_EVENTS = 20;
    public static final int MAX_RECENT_CTD_SWITCHES = 20;

    public RiskSnapshot {
        positions = List.copyOf(positions);
        recentLifecycleEvents = List.copyOf(recentLifecycleEvents);
        futures = List.copyOf(futures);
        recentCtdSwitches = List.copyOf(recentCtdSwitches);
        swaps = List.copyOf(swaps);
    }

    /** Appends new events to the recent ones, keeping only the latest {@code max}. */
    static <T> List<T> appendRecent(List<T> recent, List<T> added, int max) {
        List<T> all = new ArrayList<>(recent);
        all.addAll(added);
        return List.copyOf(all.subList(Math.max(0, all.size() - max), all.size()));
    }

    /**
     * Applies the next Risk Update. This defines what a Risk Update means: clients that apply every
     * Risk Update to a Risk Snapshot stay identical to the server's current snapshot.
     */
    public RiskSnapshot withUpdate(RiskUpdate update) {
        if (update.sequence() != sequence + 1) {
            throw new IllegalArgumentException(
                    "Risk Update " + update.sequence() + " does not follow snapshot sequence " + sequence);
        }
        Map<String, PositionResult> merged = new LinkedHashMap<>();
        positions.forEach(p -> merged.put(p.positionId(), p));
        update.positions().forEach(p -> merged.put(p.positionId(), p));
        SessionInfo newSession = update.valuationDate() == null ? session : session.withValuationDate(update.valuationDate());
        BookRisk newBookRisk = update.bookRisk() == null ? bookRisk : update.bookRisk();
        CurveView newCurve = update.curve() == null ? curve
                : new CurveView(update.curve().pillars(), update.curve().points(), curve.parInputs());
        return new RiskSnapshot(update.sequence(), update.tick(), update.ticksUntilDayRollover(), newSession,
                new ArrayList<>(merged.values()), newBookRisk, newCurve,
                appendRecent(recentLifecycleEvents, update.lifecycleEvents(), MAX_RECENT_LIFECYCLE_EVENTS),
                update.telemetry(), update.futures(),
                appendRecent(recentCtdSwitches, update.ctdSwitches(), MAX_RECENT_CTD_SWITCHES), update.credit(),
                update.swaps() == null ? swaps : update.swaps());
    }

    /**
     * @param seed                        the seed all simulation randomness derives from
     * @param simulatedSecondsPerTick     simulated time one Tick advances
     * @param ticksPerDay                 Ticks per simulated day, between Day Rollovers
     * @param stopAtTick                  the Tick the simulation stops at, or null if it runs indefinitely
     */
    public record SessionInfo(
            String curveSource,
            String curveDate,
            String valuationDate,
            long seed,
            long simulatedSecondsPerTick,
            int ticksPerDay,
            Long stopAtTick) {

        SessionInfo withValuationDate(String newValuationDate) {
            return new SessionInfo(curveSource, curveDate, newValuationDate, seed, simulatedSecondsPerTick,
                    ticksPerDay, stopAtTick);
        }
    }

    public record PositionResult(
            String positionId,
            String instrumentId,
            String instrumentType,
            String description,
            double quantity,
            double cleanPrice,
            double accruedInterest,
            double dirtyPrice,
            double value,
            double dv01,
            List<BucketDv01> bucketedDv01,
            double cs01,
            String ratingBucket,
            long lastPricedTick) {

        public PositionResult {
            bucketedDv01 = List.copyOf(bucketedDv01);
        }
    }

    /**
     * Book-level risk rolled up from Position contributions.
     *
     * @param value            Book dirty value, the sum of Position values
     * @param dv01             Book DV01, the sum of Position DV01s
     * @param bucketedDv01     Book Bucketed DV01, one entry per Pillar
     * @param cs01             Book CS01, the sum of Position CS01s
     * @param byInstrumentType totals per Instrument type, for the types held in the Book
     * @param byRatingBucket   totals per Rating Bucket, by each issuer's current rating; every bucket is
     *                         listed, so exposure visibly moves between them
     */
    public record BookRisk(
            double value,
            double dv01,
            List<BucketDv01> bucketedDv01,
            double cs01,
            List<InstrumentTypeRisk> byInstrumentType,
            List<RatingBucketRisk> byRatingBucket) {

        public BookRisk {
            bucketedDv01 = List.copyOf(bucketedDv01);
            byInstrumentType = List.copyOf(byInstrumentType);
            byRatingBucket = List.copyOf(byRatingBucket);
        }
    }

    public record RatingBucketRisk(String ratingBucket, int positionCount, double value, double dv01, double cs01) {
    }

    /**
     * What the risk engine sees of the credit market (never Latent Spreads).
     *
     * @param systemicBp the Systemic Factor
     * @param sectors    each Rating Bucket's Sector Factor
     */
    public record CreditView(double systemicBp, List<SectorLevel> sectors, List<IssuerView> issuers) {

        public CreditView {
            sectors = List.copyOf(sectors);
            issuers = List.copyOf(issuers);
        }
    }

    public record SectorLevel(String ratingBucket, double levelBp) {
    }

    /**
     * An issuer's Mark and the evidence behind it.
     *
     * @param markBp        the Mark the issuer's bonds are priced with
     * @param lastPrintBp   the latest Print, or null if none yet; likewise the Quote
     * @param printsPerYear the current Print intensity: how liquid the issuer is, including any burst after a
     *                      Credit Event
     * @param migratedFrom  the Rating Bucket before the latest Rating Migration, or null if none
     * @param migrationTick the Tick of the latest Rating Migration, or null if none
     * @param markStale     true after a Rating Migration until the next Print or Quote: "downgraded, Mark stale"
     */
    public record IssuerView(
            String issuerId,
            String name,
            String ratingBucket,
            double markBp,
            Double lastPrintBp,
            Long lastPrintTick,
            Double lastQuoteBp,
            Long lastQuoteTick,
            double printsPerYear,
            String migratedFrom,
            Long migrationTick,
            boolean markStale) {
    }

    /**
     * A cash flow paid to a Position, processed at the Day Rollover onto (or past) its payment date.
     *
     * @param tick          the Tick whose Day Rollover processed it
     * @param kind          COUPON or REDEMPTION
     * @param amountPer100  paid per 100 of face
     * @param amount        paid to the Position: per-unit amount times signed quantity (negative when short)
     */
    public record LifecycleEvent(
            long tick,
            String date,
            String positionId,
            String instrumentId,
            String description,
            String kind,
            double amountPer100,
            double amount) {
    }

    /**
     * Selective repricing made visible: how much of the Book the latest cycle repriced,
     * and the Staleness that saving costs.
     *
     * @param instrumentsRepriced  Instruments repriced in the latest cycle
     * @param instrumentsTotal     distinct Instruments in the Book
     * @param maxStaleness         per thresholded factor type, the largest move of any dependency since its
     *                             Instrument was last priced
     * @param ticksCoalesced       Ticks merged into the latest cycle beyond the one it priced
     * @param totalTicksCoalesced  Ticks coalesced since the session started
     */
    public record RepricingTelemetry(int instrumentsRepriced, int instrumentsTotal, List<FactorStaleness> maxStaleness,
                                     int ticksCoalesced, long totalTicksCoalesced) {

        public RepricingTelemetry {
            maxStaleness = List.copyOf(maxStaleness);
        }
    }

    /** @param maxStaleness never more than {@code threshold}, in {@code unit} */
    public record FactorStaleness(String factorType, String unit, double maxStaleness, double threshold) {
    }

    /**
     * A Treasury futures contract's market state.
     *
     * @param proxyBondId        the current Proxy Bond, standing in for the CTD
     * @param conversionFactor   the current Proxy Bond's conversion factor
     * @param basis              in price points per 100 face
     * @param ctdSwitchCount     CTD Switches since the session started
     * @param lastCtdSwitchTick  the Tick of the latest CTD Switch, or null if there has been none
     */
    public record FuturesView(
            String contract,
            String description,
            String proxyBondId,
            String proxyBondDescription,
            double conversionFactor,
            double basis,
            int ctdSwitchCount,
            Long lastCtdSwitchTick) {
    }

    /**
     * A CTD Switch: the contract's Proxy Bond was replaced and its Basis jumped.
     *
     * @param basisJump in price points
     */
    public record CtdSwitchEvent(long tick, String contract, String fromProxyBondId, String toProxyBondId,
                                 double basisJump) {
    }

    /**
     * An interest rate swap's floating leg as of the Valuation Date: the current period's coupon is known
     * from its Fixing; the rest are projected off the curve.
     *
     * @param currentPeriodStart null when the swap has not started yet or has matured; likewise the others
     * @param currentFixing      the Fixing for the current period, as a decimal rate
     */
    public record SwapView(
            String instrumentId,
            String description,
            String direction,
            double fixedRate,
            String currentPeriodStart,
            String currentPeriodEnd,
            Double currentFixing,
            String nextResetDate) {
    }

    /** DV01 for a 1bp bump at one Pillar, fading to zero at the neighbouring Pillars. */
    public record BucketDv01(String pillar, double years, double dv01) {
    }

    public record InstrumentTypeRisk(String instrumentType, int positionCount, double value, double dv01, double cs01) {
    }

    public record CurveView(List<CurvePoint> pillars, List<CurvePoint> points, List<ParInput> parInputs) {
    }

    /** A continuously compounded zero rate at a tenor. */
    public record CurvePoint(String label, double years, double zeroRate) {
    }

    /** One published par yield the session was calibrated to. */
    public record ParInput(String tenor, double years, double parYield) {
    }
}
