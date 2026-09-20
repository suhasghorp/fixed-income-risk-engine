package com.fixedincomerisk.model;

/**
 * The FX spot process d(ln S) = −½σ²·dt + σ·dW: driftless geometric Brownian motion, so E[S(t)] = S(0)
 * and the currency is handed no expected return. Time is in years.
 *
 * @param volatility σ, per √year
 */
public record FxSpotParameters(double volatility) {

    public FxSpotParameters {
        if (!(volatility >= 0)) {
            throw new IllegalArgumentException("FX spot volatility must be non-negative, got " + volatility);
        }
    }
}
