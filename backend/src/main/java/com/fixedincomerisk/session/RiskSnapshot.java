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
 * @param fx                     the FX market and each FX Forward's terms, Fixing and quoted forward
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
        List<SwapView> swaps,
        FxView fx) {

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
                update.swaps() == null ? swaps : update.swaps(),
                update.fx() == null ? fx : update.fx());
    }

    /**
     * @param seed                        the seed all simulation randomness derives from
     * @param simulatedSecondsPerTick     simulated time one Tick advances
     * @param ticksPerDay                 Ticks per simulated day, between Day Rollovers
     * @param stopAtTick                  the Tick the simulation stops at, or null if it runs indefinitely
     */
    /**
     * @param curveSource the Reporting Currency's Curve Source; {@code curves} has every currency's
     * @param curveDate   the Reporting Currency's curve date, likewise
     * @param curves      one entry per currency the session simulates, in market order
     */
    public record SessionInfo(
            String curveSource,
            String curveDate,
            List<CurveSourceInfo> curves,
            String valuationDate,
            long seed,
            long simulatedSecondsPerTick,
            int ticksPerDay,
            Long stopAtTick) {

        public SessionInfo {
            curves = List.copyOf(curves);
        }

        SessionInfo withValuationDate(String newValuationDate) {
            return new SessionInfo(curveSource, curveDate, curves, newValuationDate, seed,
                    simulatedSecondsPerTick, ticksPerDay, stopAtTick);
        }
    }

    /**
     * Where one currency's starting curve came from. Reported per currency, because the two arrive by
     * different routes and can fall back independently: Treasury's par curve may be live while the ECB's
     * spot rates come from the bundled snapshot.
     *
     * @param source LIVE, CACHED or BUNDLED
     * @param quotes what the publisher quoted: PAR_YIELD or ZERO_RATE
     */
    public record CurveSourceInfo(String currency, String source, String date, String quotes) {
    }

    /**
     * @param dv01           across every curve, a basis point of each; the per-currency split is in
     *                       {@code ratesByCurrency}, and only that splits cleanly
     * @param bucketedDv01   likewise, summed across currencies at each Pillar
     * @param ratesByCurrency one entry per currency the session simulates, in market order
     */
    public record PositionResult(
            String positionId,
            String instrumentId,
            String instrumentType,
            String description,
            double quantity,
            /** The currency the quantity is denominated in; not the valuation currency for an FX Forward. */
            String notionalCurrency,
            double cleanPrice,
            double accruedInterest,
            double dirtyPrice,
            double value,
            double dv01,
            List<BucketDv01> bucketedDv01,
            List<CurrencyRates> ratesByCurrency,
            double cs01,
            /** Value change for a 1% move in each currency against the Reporting Currency. */
            List<CurrencyAmount> fxDelta,
            /** Value change for a one-pip move in each NDF pair's Forward Points, spot held fixed. */
            List<PairAmount> pointsDelta,
            String ratingBucket,
            long lastPricedTick) {

        public PositionResult {
            bucketedDv01 = List.copyOf(bucketedDv01);
            ratesByCurrency = List.copyOf(ratesByCurrency);
            fxDelta = List.copyOf(fxDelta);
            pointsDelta = List.copyOf(pointsDelta);
        }
    }

    /** An amount attributed to one currency. Currencies do not net, so these are never summed together. */
    public record CurrencyAmount(String currency, double amount) {
    }

    /** An amount attributed to one currency pair. */
    public record PairAmount(String pair, double amount) {
    }

    /**
     * Book-level risk rolled up from Position contributions.
     *
     * @param value            Book dirty value, the sum of Position values
     * @param dv01             the headline total: every curve bumped a basis point each, which is not a
     *                         basis point of any one currency. Shown as
     *                         {@value com.fixedincomerisk.risk.RatesSensitivities#TOTAL_LABEL}.
     * @param bucketedDv01     the same total per Pillar
     * @param ratesByCurrency  the rates risk that does net: one entry per currency, each from bumping that
     *                         currency's curve alone; every currency is listed, even with nothing in it
     * @param cs01             Book CS01, the sum of Position CS01s
     * @param byInstrumentType totals per Instrument type, for the types held in the Book
     * @param byRatingBucket   totals per Rating Bucket, by each issuer's current rating; every bucket is
     *                         listed, so exposure visibly moves between them
     */
    public record BookRisk(
            double value,
            double dv01,
            List<BucketDv01> bucketedDv01,
            List<CurrencyRates> ratesByCurrency,
            double cs01,
            /** FX Delta per currency. There is deliberately no total: these are different risks. */
            List<CurrencyAmount> fxDeltaByCurrency,
            /** Points delta per NDF pair, reported apart from FX Delta as a future's Basis is from its DV01. */
            List<PairAmount> pointsDeltaByPair,
            List<InstrumentTypeRisk> byInstrumentType,
            List<RatingBucketRisk> byRatingBucket) {

        public BookRisk {
            bucketedDv01 = List.copyOf(bucketedDv01);
            ratesByCurrency = List.copyOf(ratesByCurrency);
            fxDeltaByCurrency = List.copyOf(fxDeltaByCurrency);
            pointsDeltaByPair = List.copyOf(pointsDeltaByPair);
            byInstrumentType = List.copyOf(byInstrumentType);
            byRatingBucket = List.copyOf(byRatingBucket);
        }
    }

    /**
     * One currency's rates risk, from bumping that currency's curve alone with every other curve held
     * fixed. This is the unit that nets: a euro basis point and a dollar one are different risks.
     */
    public record CurrencyRates(String currency, double dv01, List<BucketDv01> bucketedDv01) {

        public CurrencyRates {
            bucketedDv01 = List.copyOf(bucketedDv01);
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
            double amount,
            /** A deliverable FX Forward settles two legs in two currencies, so each event names its own. */
            String currency) {
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

    /**
     * The FX market behind the Book's FX Positions: the simulated rates, and each contract's terms next
     * to the rate it would be struck at today. The panel exists so the article can show the market data
     * behind the price, as the swaps and futures panels do.
     */
    public record FxView(List<FxPairView> pairs, List<FxContractView> contracts) {

        public FxView {
            pairs = List.copyOf(pairs);
            contracts = List.copyOf(contracts);
        }
    }

    /**
     * @param riskCurrency the side FX Delta is reported against
     * @param points       Forward Points in pips, or null for a deliverable pair, which has none
     */
    public record FxPairView(String pair, String riskCurrency, double spot, Double points) {
    }

    /**
     * @param kind         OUTRIGHT or NDF
     * @param forwardRate  derived from two curves for an outright, quoted as spot plus points for an NDF
     * @param fixingDate   the NDF's fixing date, or null for an outright, which has no fixing
     * @param fxFixing     the recorded FX Fixing, or null while the fixing date is still ahead
     */
    public record FxContractView(
            String instrumentId,
            String description,
            String kind,
            String pair,
            String direction,
            String notionalCurrency,
            double contractRate,
            double forwardRate,
            String fixingDate,
            Double fxFixing,
            String settlementDate) {
    }

    /** A continuously compounded zero rate at a tenor. */
    public record CurvePoint(String label, double years, double zeroRate) {
    }

    /** One published par yield the session was calibrated to. */
    public record ParInput(String tenor, double years, double parYield) {
    }
}
