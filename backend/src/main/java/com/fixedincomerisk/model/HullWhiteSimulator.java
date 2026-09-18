package com.fixedincomerisk.model;

import com.fixedincomerisk.market.YieldCurve;

/**
 * Evolves the Hull-White short rate by Euler-Maruyama:
 * r(t+dt) = r(t) + [θ(t) − a·r(t)]·dt + σ·√dt·Z. Shocks are supplied by the caller, so all randomness
 * stays with the session's seeded source.
 */
public final class HullWhiteSimulator {

    private final HullWhiteModel model;
    private double time;
    private double shortRate;

    public HullWhiteSimulator(HullWhiteModel model) {
        this(model, 0, model.initialShortRate());
    }

    public HullWhiteSimulator(HullWhiteModel model, double time, double shortRate) {
        this.model = model;
        this.time = time;
        this.shortRate = shortRate;
    }

    /** Advances by {@code dt} years using a standard normal {@code shock}. */
    public void advance(double dt, double shock) {
        HullWhiteParameters parameters = model.parameters();
        shortRate += (model.theta(time) - parameters.meanReversion() * shortRate) * dt
                + parameters.volatility() * Math.sqrt(dt) * shock;
        time += dt;
    }

    public double time() {
        return time;
    }

    public double shortRate() {
        return shortRate;
    }

    /** The whole curve implied by the current state. */
    public YieldCurve curve() {
        return model.curveAt(time, shortRate);
    }
}
