package com.fixedincomerisk.model;

/**
 * One Surface Point's Normal Volatility process. The OU variable is the <em>logarithm</em> of the vol:
 * d(ln σ) = κ·(ln σ̄ − ln σ)·dt + η·dW, with time in years. κ and η are global, as the futures Basis's
 * are; only the long-run mean is per point, because that is what distinguishes one Surface Point from
 * another.
 *
 * <p>Taking the process on the logarithm rather than the level costs one line and is what makes the
 * Normal Volatility positive by construction. A level-OU vol diffuses through zero on a long enough run
 * from a low mean, and a negative Normal Volatility is not a market state — it is an option priced at a
 * negative premium.
 *
 * @param meanReversion κ, per year
 * @param volOfVol      η, the volatility of log vol, per √year; a relative move, so it is unitless
 * @param longRunMean   σ̄, in decimal (0.0095 is 95bp); the Normal Volatility starts here
 */
public record NormalVolParameters(double meanReversion, double volOfVol, double longRunMean) {

    public NormalVolParameters {
        if (meanReversion < 0 || volOfVol < 0) {
            throw new IllegalArgumentException("Normal Volatility κ and η must be non-negative");
        }
        if (!(longRunMean > 0)) {
            throw new IllegalArgumentException(
                    "A Normal Volatility's long-run mean must be positive, got " + longRunMean);
        }
    }
}
