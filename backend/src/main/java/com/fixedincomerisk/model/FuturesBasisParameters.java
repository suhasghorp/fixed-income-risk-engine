package com.fixedincomerisk.model;

/**
 * The futures Basis process dB = κ·(B̄ − B)·dt + η·dW, plus CTD Switches. Basis values are
 * in price points per 100 face; time is in years.
 *
 * @param meanReversion       κ, per year
 * @param longRunMean         B̄, in price points; the Basis starts here
 * @param volatility          η, in price points per √year
 * @param ctdSwitchIntensity  λ, the Poisson rate of CTD Switches per contract, per year
 * @param ctdSwitchJump       the size of the Basis jump on a CTD Switch, in price points; its sign is random
 */
public record FuturesBasisParameters(
        double meanReversion,
        double longRunMean,
        double volatility,
        double ctdSwitchIntensity,
        double ctdSwitchJump) {

    public FuturesBasisParameters {
        if (meanReversion < 0 || volatility < 0 || ctdSwitchIntensity < 0 || ctdSwitchJump < 0) {
            throw new IllegalArgumentException("Basis parameters must be non-negative (except the long-run mean)");
        }
    }
}
