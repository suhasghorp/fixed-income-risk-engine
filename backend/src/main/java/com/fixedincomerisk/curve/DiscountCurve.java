package com.fixedincomerisk.curve;

/**
 * A continuous discount function P(0,T), built from knots of ln P(0,T) with shape-preserving
 * interpolation. Beyond the last knot, the instantaneous forward rate is held flat.
 */
public final class DiscountCurve {

    private final MonotoneCubicInterpolator logDiscount;

    private DiscountCurve(MonotoneCubicInterpolator logDiscount) {
        this.logDiscount = logDiscount;
    }

    /** Knots at times > 0; the knot ln P(0,0) = 0 is added. */
    public static DiscountCurve fromLogDiscountFactors(double[] times, double[] logDiscountFactors) {
        double[] t = new double[times.length + 1];
        double[] lnP = new double[times.length + 1];
        System.arraycopy(times, 0, t, 1, times.length);
        System.arraycopy(logDiscountFactors, 0, lnP, 1, times.length);
        return new DiscountCurve(new MonotoneCubicInterpolator(t, lnP));
    }

    /** Knots given as continuously compounded zero rates at times > 0. */
    public static DiscountCurve fromZeroRates(double[] times, double[] zeroRates) {
        double[] lnP = new double[times.length];
        for (int i = 0; i < times.length; i++) {
            lnP[i] = -zeroRates[i] * times[i];
        }
        return fromLogDiscountFactors(times, lnP);
    }

    public double discountFactor(double t) {
        return Math.exp(logDiscountFactor(t));
    }

    public double logDiscountFactor(double t) {
        if (t < 0) {
            throw new IllegalArgumentException("Negative time: " + t);
        }
        double last = logDiscount.lastKnot();
        if (t <= last) {
            return logDiscount.value(t);
        }
        return logDiscount.value(last) + logDiscount.derivative(last) * (t - last);
    }

    /** Continuously compounded zero rate; at t = 0 this is the instantaneous short forward. */
    public double zeroRate(double t) {
        return t <= 0 ? instantaneousForward(0) : -logDiscountFactor(t) / t;
    }

    /** f(0,t) = -d ln P(0,t) / dt. */
    public double instantaneousForward(double t) {
        double last = logDiscount.lastKnot();
        return -logDiscount.derivative(Math.min(Math.max(t, 0), last));
    }
}
