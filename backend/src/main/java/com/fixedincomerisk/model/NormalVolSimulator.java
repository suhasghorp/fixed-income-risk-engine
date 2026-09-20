package com.fixedincomerisk.model;

/**
 * Evolves one Surface Point's Normal Volatility as an Ornstein-Uhlenbeck process by Euler-Maruyama, the
 * same shape as the futures Basis — but on the logarithm of the vol rather than on the level:
 * ln σ(t+dt) = ln σ(t) + κ·(ln σ̄ − ln σ(t))·dt + η·√dt·Z.
 *
 * <p>Mean reversion is about the most robust empirical fact in rates volatility, so the shape is
 * defensible rather than merely convenient. Stepping the logarithm is what keeps σ positive whatever the
 * shock, exactly as {@link FxSpotSimulator} keeps spot positive; a Normal Volatility that diffuses
 * through zero prices options at a negative premium.
 */
public final class NormalVolSimulator {

    private final NormalVolParameters parameters;
    private final double logLongRunMean;
    private double logVol;

    public NormalVolSimulator(NormalVolParameters parameters) {
        this(parameters, parameters.longRunMean());
    }

    /** Starting away from the long-run mean, as {@link NdfPointsSimulator} allows for Forward Points. */
    public NormalVolSimulator(NormalVolParameters parameters, double startingVol) {
        if (!(startingVol > 0)) {
            throw new IllegalArgumentException("A Normal Volatility must start positive, got " + startingVol);
        }
        this.parameters = parameters;
        this.logLongRunMean = Math.log(parameters.longRunMean());
        this.logVol = Math.log(startingVol);
    }

    public void advance(double dt, double shock) {
        logVol += parameters.meanReversion() * (logLongRunMean - logVol) * dt
                + parameters.volOfVol() * Math.sqrt(dt) * shock;
    }

    /** The Normal Volatility, in decimal: 0.0095 is 95bp per annum. */
    public double vol() {
        return Math.exp(logVol);
    }

    /** The OU variable itself, which is what a test of the process's shape has to look at. */
    public double logVol() {
        return logVol;
    }
}
