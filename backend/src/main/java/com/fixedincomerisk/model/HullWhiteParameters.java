package com.fixedincomerisk.model;

/**
 * @param meanReversion a — speed of mean reversion
 * @param volatility    σ — instantaneous short-rate volatility
 */
public record HullWhiteParameters(double meanReversion, double volatility) {

    public HullWhiteParameters {
        if (meanReversion <= 0 || volatility < 0) {
            throw new IllegalArgumentException("Hull-White needs a > 0 and σ >= 0");
        }
    }
}
