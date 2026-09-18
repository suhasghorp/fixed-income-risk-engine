package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Everything Instruments are priced against. Only USD exists for now.
 *
 * @param futures each Treasury futures contract's simulated state, by contract id
 * @param credit  the observable credit market and the Marks (never Latent Spreads)
 * @param fixings the floating index's recorded Fixings
 */
public record MarketState(LocalDate valuationDate, YieldCurve curve, Map<String, FuturesMarket> futures,
                          CreditMarket credit, Fixings fixings) {

    public static final String CURRENCY = "USD";

    public MarketState {
        futures = Map.copyOf(futures);
    }

    /** A market with no futures, credit or Fixings. */
    public MarketState(LocalDate valuationDate, YieldCurve curve) {
        this(valuationDate, curve, Map.of(), CreditMarket.NONE, Fixings.NONE);
    }

    /** A market with no Fixings. */
    public MarketState(LocalDate valuationDate, YieldCurve curve, Map<String, FuturesMarket> futures,
                       CreditMarket credit) {
        this(valuationDate, curve, futures, credit, Fixings.NONE);
    }

    /** The same market with other Fixings. */
    public MarketState withFixings(Fixings newFixings) {
        return new MarketState(valuationDate, curve, futures, credit, newFixings);
    }

    /** The same market on another curve: used to bump the curve with everything else held fixed. */
    public MarketState withCurve(YieldCurve newCurve) {
        return new MarketState(valuationDate, newCurve, futures, credit, fixings);
    }

    /** The same market with every Mark shifted by {@code shift}: used to bump spreads for CS01. */
    public MarketState withMarksShiftedBy(double shift) {
        return new MarketState(valuationDate, curve, futures, credit.withMarksShiftedBy(shift), fixings);
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
        if (!factor.currency().equals(CURRENCY)) {
            throw new IllegalArgumentException("No market for currency " + factor.currency());
        }
        return switch (factor.type()) {
            case PILLAR_ZERO_RATE -> curve.zeroRate(Pillar.parse(factor.name()).years());
            case MARK -> mark(factor.name());
            case SYSTEMIC -> credit.systemic();
            case SECTOR -> credit.sector(factor.name());
            case RATING -> credit.ratingIndex(factor.name());
            case BASIS -> futures(factor.name()).basis();
            case PROXY_BOND -> futures(factor.name()).proxyIndex();
            case VALUATION_DATE -> valuationDate.toEpochDay();
        };
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
