package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Everything Instruments are priced against, across every currency the session simulates.
 *
 * <p>There is no "the curve": every caller names the currency it means, every time. An implicit default
 * is how euro cash flows end up discounted on the dollar curve with nothing failing, so
 * {@link #curve(String)} is the only way in.
 *
 * @param curves  one zero curve per currency, in the order the session configured them
 * @param futures each Treasury futures contract's simulated state, by contract id
 * @param credit  the observable credit market and the Marks (never Latent Spreads)
 * @param fixings the floating index's recorded Fixings
 * @param fx      the simulated FX market: spot per pair, and Forward Points for the non-deliverable ones
 */
public record MarketState(LocalDate valuationDate, Map<String, YieldCurve> curves,
                          Map<String, FuturesMarket> futures, CreditMarket credit, Fixings fixings,
                          FxMarket fx) {

    public MarketState {
        // LinkedHashMap, not Map.copyOf: currency order decides the order risk is reported in.
        curves = Collections.unmodifiableMap(new LinkedHashMap<>(curves));
        futures = Map.copyOf(futures);
    }

    /** A market with no FX. */
    public MarketState(LocalDate valuationDate, Map<String, YieldCurve> curves,
                       Map<String, FuturesMarket> futures, CreditMarket credit, Fixings fixings) {
        this(valuationDate, curves, futures, credit, fixings, FxMarket.NONE);
    }

    /** A market with one currency's curve and no futures, credit or Fixings. */
    public static MarketState of(LocalDate valuationDate, String currency, YieldCurve curve) {
        return new MarketState(valuationDate, Map.of(currency, curve));
    }

    /** A market with no futures, credit or Fixings. */
    public MarketState(LocalDate valuationDate, Map<String, YieldCurve> curves) {
        this(valuationDate, curves, Map.of(), CreditMarket.NONE, Fixings.NONE);
    }

    /** A market with no Fixings. */
    public MarketState(LocalDate valuationDate, Map<String, YieldCurve> curves,
                       Map<String, FuturesMarket> futures, CreditMarket credit) {
        this(valuationDate, curves, futures, credit, Fixings.NONE);
    }

    /** The currencies this market has a curve for, in configuration order. */
    public List<String> currencies() {
        return List.copyOf(curves.keySet());
    }

    /** {@code currency}'s zero curve; fails rather than falling back to another currency's. */
    public YieldCurve curve(String currency) {
        YieldCurve curve = curves.get(currency);
        if (curve == null) {
            throw noMarketFor(currency);
        }
        return curve;
    }

    /** The same market with other Fixings. */
    public MarketState withFixings(Fixings newFixings) {
        return new MarketState(valuationDate, curves, futures, credit, newFixings, fx);
    }

    /** The same market with another FX market: used to bump spot or points with everything else fixed. */
    public MarketState withFx(FxMarket newFx) {
        return new MarketState(valuationDate, curves, futures, credit, fixings, newFx);
    }

    /**
     * The same market with one currency's curve replaced: used to bump that curve with every other
     * curve, and everything else, held fixed.
     */
    public MarketState withCurve(String currency, YieldCurve newCurve) {
        Map<String, YieldCurve> bumped = new LinkedHashMap<>(curves);
        if (bumped.put(currency, newCurve) == null) {
            throw noMarketFor(currency);
        }
        return new MarketState(valuationDate, bumped, futures, credit, fixings, fx);
    }

    private IllegalArgumentException noMarketFor(String currency) {
        return new IllegalArgumentException("No market for currency " + currency + "; this market has " + currencies());
    }

    /** The same market with every Mark shifted by {@code shift}: used to bump spreads for CS01. */
    public MarketState withMarksShiftedBy(double shift) {
        return new MarketState(valuationDate, curves, futures, credit.withMarksShiftedBy(shift), fixings, fx);
    }

    public double mark(String issuerId) {
        return credit.mark(issuerId);
    }

    public FuturesMarket futures(String contract) {
        FuturesMarket state = futures.get(contract);
        if (state == null) {
            throw new IllegalArgumentException("No futures market for " + contract);
        }
        return state;
    }

    /**
     * The current value of a Risk Factor, in its raw unit: a decimal rate or spread, price points, or, for
     * discrete factors, a number that changes exactly when the factor does.
     */
    public double riskFactorValue(RiskFactorId factor) {
        // FX factors come first: an NDF's currency has no curve, which is the whole reason its market
        // quotes Forward Points instead of deriving them.
        switch (factor.type()) {
            case FX_SPOT -> {
                return Math.log(fx.spot(factor.name()));
            }
            case NDF_POINTS -> {
                return fx.points(factor.name());
            }
            default -> { }
        }
        // Looking the curve up first also rejects a factor in a currency this market does not have.
        YieldCurve curve = curve(factor.currency());
        return switch (factor.type()) {
            case PILLAR_ZERO_RATE -> curve.zeroRate(Pillar.parse(factor.name()).years());
            case MARK -> mark(factor.name());
            case SYSTEMIC -> credit.systemic();
            case SECTOR -> credit.sector(factor.name());
            case RATING -> credit.ratingIndex(factor.name());
            case BASIS -> futures(factor.name()).basis();
            case PROXY_BOND -> futures(factor.name()).proxyIndex();
            case VALUATION_DATE -> valuationDate.toEpochDay();
            case FX_SPOT, NDF_POINTS -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * What the risk engine sees of the FX market.
     *
     * @param spot   each pair's FX Spot, by pair, e.g. "EURUSD" to 1.146
     * @param points each non-deliverable pair's Forward Points, in pips. A deliverable pair has none.
     * @param fixings the FX Fixings recorded so far, which set NDF settlements and never change
     */
    public record FxMarket(Map<String, Double> spot, Map<String, Double> points, FxFixings fixings) {

        public static final FxMarket NONE = new FxMarket(Map.of(), Map.of(), FxFixings.NONE);

        /** An FX market with nothing fixed yet. */
        public FxMarket(Map<String, Double> spot, Map<String, Double> points) {
            this(spot, points, FxFixings.NONE);
        }

        public FxMarket {
            spot = Collections.unmodifiableMap(new LinkedHashMap<>(spot));
            points = Collections.unmodifiableMap(new LinkedHashMap<>(points));
        }

        /** The pairs with a simulated spot, in configuration order. */
        public List<String> pairs() {
            return List.copyOf(spot.keySet());
        }

        public double spot(String pair) {
            Double rate = spot.get(pair);
            if (rate == null) {
                throw new IllegalArgumentException("No FX Spot for " + pair + "; this market has " + pairs());
            }
            return rate;
        }

        /** Forward Points in pips; fails for a deliverable pair, which derives its forward from curves. */
        public double points(String pair) {
            Double pips = points.get(pair);
            if (pips == null) {
                throw new IllegalArgumentException("No Forward Points for " + pair
                        + "; points are quoted for " + List.copyOf(points.keySet()));
            }
            return pips;
        }

        /** The same FX market with one pair's spot replaced: used to bump it for FX Delta. */
        public FxMarket withSpot(String pair, double newSpot) {
            Map<String, Double> bumped = new LinkedHashMap<>(spot);
            if (bumped.put(pair, newSpot) == null) {
                throw new IllegalArgumentException("No FX Spot for " + pair + "; this market has " + pairs());
            }
            return new FxMarket(bumped, points, fixings);
        }

        /** The same FX market with one pair's Forward Points replaced: used to bump them for points delta. */
        public FxMarket withPoints(String pair, double newPoints) {
            Map<String, Double> bumped = new LinkedHashMap<>(points);
            if (bumped.put(pair, newPoints) == null) {
                throw new IllegalArgumentException("No Forward Points for " + pair);
            }
            return new FxMarket(spot, bumped, fixings);
        }
    }

    /**
     * @param proxyIndex which of the contract's Proxy Bonds currently stands in for the CTD
     * @param basis      the Basis, in price points per 100 face
     */
    public record FuturesMarket(int proxyIndex, double basis) {
    }

    /**
     * What the risk engine may see of the credit market. Spreads are decimal.
     *
     * @param systemic the Systemic Factor
     * @param sectors  each Rating Bucket's Sector Factor, by bucket label
     * @param ratings  each issuer's public Rating Bucket label, by issuer id
     * @param marks    each issuer's Mark: the flat, continuously compounded Z-spread its bonds are priced with
     */
    public record CreditMarket(double systemic, Map<String, Double> sectors, Map<String, String> ratings,
                               Map<String, Double> marks) {

        public static final CreditMarket NONE = new CreditMarket(0, Map.of(), Map.of(), Map.of());

        public CreditMarket {
            sectors = Map.copyOf(sectors);
            ratings = Map.copyOf(ratings);
            marks = Map.copyOf(marks);
        }

        public double mark(String issuerId) {
            return require(marks, issuerId, "Mark for issuer");
        }

        public double sector(String ratingBucket) {
            return require(sectors, ratingBucket, "Sector Factor for");
        }

        public String rating(String issuerId) {
            return require(ratings, issuerId, "rating for issuer");
        }

        /** The rating's position among the bucket labels: changes exactly when the rating does. */
        double ratingIndex(String issuerId) {
            return new TreeSet<>(sectors.keySet()).headSet(rating(issuerId)).size();
        }

        CreditMarket withMarksShiftedBy(double shift) {
            Map<String, Double> shifted = new HashMap<>();
            marks.forEach((issuer, mark) -> shifted.put(issuer, mark + shift));
            return new CreditMarket(systemic, sectors, ratings, shifted);
        }

        private static <T> T require(Map<String, T> values, String key, String what) {
            T value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("No " + what + " " + key);
            }
            return value;
        }
    }
}
