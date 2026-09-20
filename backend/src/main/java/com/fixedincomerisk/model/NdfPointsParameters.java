package com.fixedincomerisk.model;

/**
 * The NDF Forward Points process dP = κ·(P̄ − P)·dt + η·dW, the same shape as the futures Basis. Points
 * are in pips; time is in years.
 *
 * @param meanReversion κ, per year
 * @param longRunMean   P̄, in pips; the points start here
 * @param volatility    η, in pips per √year
 */
public record NdfPointsParameters(double meanReversion, double longRunMean, double volatility) {

    public NdfPointsParameters {
        if (meanReversion < 0 || volatility < 0) {
            throw new IllegalArgumentException("NDF points κ and η must be non-negative (the mean may be either)");
        }
    }
}
