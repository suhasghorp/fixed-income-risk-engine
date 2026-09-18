package com.fixedincomerisk.model;

import com.fixedincomerisk.curve.DiscountCurve;
import com.fixedincomerisk.market.YieldCurve;

/**
 * One-factor Hull-White short-rate model, dr = [θ(t) − a·r] dt + σ dW, calibrated so that it
 * reproduces the initial discount curve exactly.
 */
public final class HullWhiteModel {

    private static final double DERIVATIVE_STEP = 1e-4;

    private final DiscountCurve initialCurve;
    private final HullWhiteParameters parameters;
    private final double a;
    private final double sigma;

    private HullWhiteModel(DiscountCurve initialCurve, HullWhiteParameters parameters) {
        this.initialCurve = initialCurve;
        this.parameters = parameters;
        this.a = parameters.meanReversion();
        this.sigma = parameters.volatility();
    }

    public static HullWhiteModel calibrate(DiscountCurve initialCurve, HullWhiteParameters parameters) {
        return new HullWhiteModel(initialCurve, parameters);
    }

    public HullWhiteParameters parameters() {
        return parameters;
    }

    /** r(0) = f(0,0). */
    public double initialShortRate() {
        return initialCurve.instantaneousForward(0);
    }

    /** θ(t) = ∂f(0,t)/∂t + a·f(0,t) + (σ²/2a)·(1 − e^(−2at)). */
    public double theta(double t) {
        double lower = Math.max(0, t - DERIVATIVE_STEP);
        double upper = t + DERIVATIVE_STEP;
        double forwardSlope = (initialCurve.instantaneousForward(upper) - initialCurve.instantaneousForward(lower))
                / (upper - lower);
        return forwardSlope + a * initialCurve.instantaneousForward(t)
                + sigma * sigma / (2 * a) * (1 - Math.exp(-2 * a * t));
    }

    /** B(t,T) = (1 − e^(−a(T−t))) / a. */
    public double b(double t, double maturity) {
        return (1 - Math.exp(-a * (maturity - t))) / a;
    }

    /** Closed-form zero-coupon bond price P(t,T) = A(t,T)·exp(−B(t,T)·r). */
    public double discountFactor(double t, double maturity, double shortRate) {
        double b = b(t, maturity);
        double lnA = initialCurve.logDiscountFactor(maturity) - initialCurve.logDiscountFactor(t)
                + b * initialCurve.instantaneousForward(t)
                - sigma * sigma / (4 * a) * (1 - Math.exp(-2 * a * t)) * b * b;
        return Math.exp(lnA - b * shortRate);
    }

    /** The whole curve at model time t given the short rate; maturities are measured from t. */
    public YieldCurve curveAt(double t, double shortRate) {
        return timeToCashFlow -> discountFactor(t, t + timeToCashFlow, shortRate);
    }
}
