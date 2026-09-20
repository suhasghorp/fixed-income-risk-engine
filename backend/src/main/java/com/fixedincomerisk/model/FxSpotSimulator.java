package com.fixedincomerisk.model;

/**
 * Evolves one currency pair's FX Spot as driftless geometric Brownian motion, stepped on the logarithm by
 * Euler-Maruyama: ln S(t+dt) = ln S(t) − ½σ²·dt + σ·√dt·Z. Shocks are supplied by the caller, so all
 * randomness stays with the session's seeded source.
 *
 * <p>This is the first process in the engine that does not mean-revert, deliberately: a spot rate pulled
 * back to a fixed level would be a free forecast of the currency. The −½σ² term is what makes it
 * driftless in spot rather than in log spot, so the simulation does not quietly hand one currency an
 * expected appreciation the two curves' forward does not imply.
 *
 * <p>Stepping the logarithm also keeps spot positive whatever the shock, which stepping the level does not.
 */
public final class FxSpotSimulator {

    private final FxSpotParameters parameters;
    private double logSpot;

    public FxSpotSimulator(double startingSpot, FxSpotParameters parameters) {
        if (!(startingSpot > 0)) {
            throw new IllegalArgumentException("FX Spot must start positive, got " + startingSpot);
        }
        this.parameters = parameters;
        this.logSpot = Math.log(startingSpot);
    }

    public void advance(double dt, double shock) {
        double sigma = parameters.volatility();
        logSpot += -0.5 * sigma * sigma * dt + sigma * Math.sqrt(dt) * shock;
    }

    public double spot() {
        return Math.exp(logSpot);
    }

    /** The Risk Factor's value: a difference in it is a relative move, which is how FX moves are measured. */
    public double logSpot() {
        return logSpot;
    }
}
