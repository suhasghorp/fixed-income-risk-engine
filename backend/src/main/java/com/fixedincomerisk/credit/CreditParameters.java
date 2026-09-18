package com.fixedincomerisk.credit;

/**
 * The credit factor hierarchy's OU processes, dX = κ·(X̄ − X)·dt + σ·dW, with time in years and levels
 * in basis points. The levels have deliberately different character: the Systemic Factor
 * is slow and wide, Sector Factors moderate, Idiosyncratic Factors small and fast. Sector and
 * Idiosyncratic long-run means are reference data, per Rating Bucket and per issuer.
 */
public record CreditParameters(
        double systemicMeanReversion,
        double systemicLongRunMeanBp,
        double systemicVolatilityBp,
        double sectorMeanReversion,
        double sectorVolatilityBp,
        double idiosyncraticMeanReversion,
        double idiosyncraticVolatilityBp) {

    public CreditParameters {
        if (systemicMeanReversion < 0 || systemicVolatilityBp < 0 || sectorMeanReversion < 0 || sectorVolatilityBp < 0
                || idiosyncraticMeanReversion < 0 || idiosyncraticVolatilityBp < 0) {
            throw new IllegalArgumentException("Mean reversion speeds and volatilities must be non-negative");
        }
    }
}
