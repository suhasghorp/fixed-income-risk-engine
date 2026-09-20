package com.fixedincomerisk.model;

/**
 * Evolves one non-deliverable pair's Forward Points as an Ornstein-Uhlenbeck process by Euler-Maruyama,
 * the same shape as the futures Basis: dP = κ·(P̄ − P)·dt + η·√dt·Z, in pips.
 *
 * <p>A deliverable pair has no points process at all — its forward is derived from the two currencies'
 * curves. Points are the primitive only where there is no curve to derive from.
 */
public final class NdfPointsSimulator {

    private final NdfPointsParameters parameters;
    private double points;

    public NdfPointsSimulator(NdfPointsParameters parameters) {
        this(parameters, parameters.longRunMean());
    }

    /** Starting away from the long-run mean, as {@link HullWhiteSimulator} allows for the short rate. */
    public NdfPointsSimulator(NdfPointsParameters parameters, double startingPoints) {
        this.parameters = parameters;
        this.points = startingPoints;
    }

    public void advance(double dt, double shock) {
        points += parameters.meanReversion() * (parameters.longRunMean() - points) * dt
                + parameters.volatility() * Math.sqrt(dt) * shock;
    }

    public double points() {
        return points;
    }
}
