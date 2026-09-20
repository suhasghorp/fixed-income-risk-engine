package com.fixedincomerisk.model;

import org.apache.commons.math3.distribution.NormalDistribution;

/**
 * The Bachelier (normal) model for a European swaption, per unit of the underlying swap's notional. With
 * {@code A} the Annuity, {@code F} the Forward Swap Rate, {@code K} the strike, {@code σ} the Normal
 * Volatility as a decimal and {@code T} the year fraction to Expiry:
 *
 * <pre>
 * d        = (F − K) / (σ√T)
 * payer    = A · [ (F − K)·Φ(d)  + σ√T·φ(d) ]
 * receiver = A · [ (K − F)·Φ(−d) + σ√T·φ(d) ]
 * vega     = A · √T · φ(d)          per unit of σ
 * </pre>
 *
 * <p>Normal rather than lognormal, because normal vol is what the swaption market has quoted since rates
 * went to zero, and because the model survives negative and near-zero forwards where Black-76 needs a
 * shift parameter invented for it. The Annuity appears as its own term, which is what ties the option
 * straight back to the curve code the rest of the Book already uses.
 *
 * <p>This is deliberately <em>not</em> {@link HullWhiteModel}: that is the simulation's generator, and
 * pricing the Book's options with the generator's own σ would make them right by construction. ADR-0011.
 *
 * <p>A and F come from the underlying {@code InterestRateSwap}, which owns the schedule and the day
 * counts. Nothing here knows what a coupon is.
 */
public final class BachelierModel {

    /** Φ, the standard normal CDF: the one primitive ADR-0004 takes from Commons Math. */
    private static final NormalDistribution STANDARD_NORMAL = new NormalDistribution();
    private static final double INVERSE_ROOT_TWO_PI = 1 / Math.sqrt(2 * Math.PI);

    private BachelierModel() {
    }

    /** The right to pay {@code strike} and receive floating: worth {@code A·(F − K)} in the money. */
    public static double payer(double annuity, double forwardRate, double strike, double normalVol,
                               double timeToExpiry) {
        double sigmaRootT = sigmaRootT(normalVol, timeToExpiry);
        if (sigmaRootT == 0) {
            return annuity * Math.max(forwardRate - strike, 0);
        }
        double d = (forwardRate - strike) / sigmaRootT;
        return annuity * ((forwardRate - strike) * cdf(d) + sigmaRootT * pdf(d));
    }

    /** The right to receive {@code strike} and pay floating: worth {@code A·(K − F)} in the money. */
    public static double receiver(double annuity, double forwardRate, double strike, double normalVol,
                                  double timeToExpiry) {
        double sigmaRootT = sigmaRootT(normalVol, timeToExpiry);
        if (sigmaRootT == 0) {
            return annuity * Math.max(strike - forwardRate, 0);
        }
        double d = (forwardRate - strike) / sigmaRootT;
        return annuity * ((strike - forwardRate) * cdf(-d) + sigmaRootT * pdf(d));
    }

    /**
     * The change in value for a move of 1.0 in the Normal Volatility, per unit of notional. It is the same
     * for a payer and a receiver: the two differ by {@code A·(F − K)}, which does not depend on σ.
     *
     * <p>At {@code T = 0} it is zero, there being no time value left to gain. At {@code σ = 0} with
     * {@code T > 0} it is the limit as σ falls to zero, which is {@code A·√T·φ(0)} at the money and zero
     * away from it — the point where a swaption's value stops being differentiable in the forward.
     */
    public static double vega(double annuity, double forwardRate, double strike, double normalVol,
                              double timeToExpiry) {
        if (timeToExpiry <= 0) {
            return 0;
        }
        double rootT = Math.sqrt(timeToExpiry);
        double sigmaRootT = sigmaRootT(normalVol, timeToExpiry);
        if (sigmaRootT == 0) {
            return forwardRate == strike ? annuity * rootT * pdf(0) : 0;
        }
        return annuity * rootT * pdf((forwardRate - strike) / sigmaRootT);
    }

    /** {@link #vega} scaled to the 1bp move Vega is reported for. */
    public static double vegaPerBasisPoint(double annuity, double forwardRate, double strike, double normalVol,
                                           double timeToExpiry) {
        return vega(annuity, forwardRate, strike, normalVol, timeToExpiry) * 1e-4;
    }

    /**
     * σ√T, the only quantity the formula divides by. Zero when the option has no volatility or no time
     * left, which is the state a Position sits in on its Expiry date — a case, not an edge case, and the
     * reason both prices degenerate to intrinsic rather than dividing by zero.
     */
    private static double sigmaRootT(double normalVol, double timeToExpiry) {
        if (normalVol < 0) {
            throw new IllegalArgumentException("A Normal Volatility cannot be negative, got " + normalVol);
        }
        if (timeToExpiry < 0) {
            throw new IllegalArgumentException("Time to Expiry cannot be negative, got " + timeToExpiry);
        }
        return normalVol * Math.sqrt(timeToExpiry);
    }

    private static double cdf(double x) {
        return STANDARD_NORMAL.cumulativeProbability(x);
    }

    /** φ, the standard normal density. Four lines of hand-written math, per ADR-0004. */
    private static double pdf(double x) {
        return INVERSE_ROOT_TWO_PI * Math.exp(-0.5 * x * x);
    }
}
