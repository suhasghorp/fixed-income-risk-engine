package com.fixedincomerisk.market;

/**
 * One simulated currency pair. Spot is units of {@code pair}'s second currency per unit of its first.
 *
 * @param pair         the pair, e.g. "EURUSD"
 * @param riskCurrency the side FX Delta is reported against: the pair's non-Reporting currency
 * @param startingSpot the session's opening FX Spot
 * @param pipSize      one pip in spot terms, e.g. 0.0001 for EURUSD and 0.01 for USDKRW
 * @param quotesPoints true for a non-deliverable pair, whose forward is spot plus quoted Forward Points
 */
public record FxPair(String pair, String riskCurrency, double startingSpot, double pipSize, boolean quotesPoints) {

    public FxPair {
        if (pair.length() != 6) {
            throw new IllegalArgumentException("A currency pair is six characters, base then quote; got " + pair);
        }
        if (!(startingSpot > 0)) {
            throw new IllegalArgumentException("FX Spot must start positive for " + pair + ", got " + startingSpot);
        }
        if (!(pipSize > 0)) {
            throw new IllegalArgumentException("Pip size must be positive for " + pair + ", got " + pipSize);
        }
    }

    /** The pair's first currency: spot is quoted as units of {@link #quoteCurrency()} per unit of this. */
    public String baseCurrency() {
        return pair.substring(0, 3);
    }

    /** The pair's second currency, which a rate is quoted in. */
    public String quoteCurrency() {
        return pair.substring(3);
    }

    /** The Risk Factor this pair's spot is identified by. */
    public RiskFactorId spotFactor() {
        return RiskFactorId.fxSpot(riskCurrency, pair);
    }

    /** The Risk Factor this pair's Forward Points are identified by; only a non-deliverable pair has one. */
    public RiskFactorId pointsFactor() {
        if (!quotesPoints) {
            throw new IllegalStateException(pair + " is deliverable: its forward is derived from curves, "
                    + "so it has no Forward Points");
        }
        return RiskFactorId.ndfPoints(riskCurrency, pair);
    }

    /**
     * The spot rate after {@link #riskCurrency()} appreciates by {@code fraction} against the other side.
     *
     * <p>The direction depends on which side of the pair the risk currency is, and getting it backwards is
     * the easiest mistake in the whole engine: EURUSD is USD per EUR, so a stronger euro means spot rises;
     * USDKRW is KRW per USD, so a stronger won means spot <em>falls</em>.
     */
    public double spotAfterRiskCurrencyMove(double spot, double fraction) {
        return riskCurrency.equals(baseCurrency()) ? spot * (1 + fraction) : spot / (1 + fraction);
    }

    /** The correlated-driver name for this pair's spot, as {@code risk.correlation.factors} spells it. */
    public String spotShockFactor() {
        return "fxSpot." + pair;
    }

    /** The correlated-driver name for this pair's Forward Points. */
    public String pointsShockFactor() {
        return "ndfPoints." + pair;
    }
}
