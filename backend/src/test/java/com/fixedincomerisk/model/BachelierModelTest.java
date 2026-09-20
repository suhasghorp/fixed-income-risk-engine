package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * The normal-model pricer, checked the three ways ADR-0004 asks for when the math is hand-written:
 * against an identity that must hold whatever the inputs (put–call parity), against a value computed a
 * second, independent way (numerical integration of the payoff), and against a closed form small enough
 * to evaluate by hand (the at-the-money price).
 */
class BachelierModelTest {

    /** A five-year annuity at about 4%: the order of magnitude of the Book's 1Mx5Y underlying. */
    private static final double ANNUITY = 4.45;
    private static final double FORWARD = 0.0412;

    /**
     * Put–call parity is the identity that does not care about the model: whatever σ and whatever strike,
     * a payer less a receiver is a forward-starting swap, worth {@code A·(F − K)}. A pricer that gets
     * either side's Φ term backwards fails this, and nothing else in the suite would catch it.
     */
    @Test
    void payerMinusReceiverIsTheUnderlyingSwapAtEveryStrikeAndVol() {
        for (double strike = -0.01; strike <= 0.09; strike += 0.0025) {
            for (double vol : new double[] {0.0001, 0.0025, 0.0060, 0.0095, 0.0250, 0.1000}) {
                for (double expiry : new double[] {1.0 / 12, 0.5, 1, 5, 30}) {
                    double payer = BachelierModel.payer(ANNUITY, FORWARD, strike, vol, expiry);
                    double receiver = BachelierModel.receiver(ANNUITY, FORWARD, strike, vol, expiry);

                    assertThat(payer - receiver)
                            .as("parity at K=%s σ=%s T=%s", strike, vol, expiry)
                            .isCloseTo(ANNUITY * (FORWARD - strike), within(1e-14));
                    assertThat(payer).as("payer at K=%s σ=%s T=%s", strike, vol, expiry).isNotNegative();
                    assertThat(receiver).as("receiver at K=%s σ=%s T=%s", strike, vol, expiry).isNotNegative();
                }
            }
        }
    }

    /**
     * The reference value, computed a completely different way: the Bachelier price is the discounted
     * expected payoff of a forward that is normally distributed about F with standard deviation σ√T, so
     * integrating {@code max(F + σ√T·z − K, 0)·φ(z)} over z reproduces it without using the closed form
     * at all. Simpson's rule on a fine grid is exact to far more digits than the comparison needs.
     */
    @Test
    void pricesMatchNumericalIntegrationOfThePayoff() {
        for (double strike : new double[] {0.0212, 0.0380, 0.0412, 0.0450, 0.0612}) {
            for (double vol : new double[] {0.0040, 0.0095, 0.0300}) {
                for (double expiry : new double[] {1.0 / 12, 1, 10}) {
                    double payer = BachelierModel.payer(ANNUITY, FORWARD, strike, vol, expiry);
                    double receiver = BachelierModel.receiver(ANNUITY, FORWARD, strike, vol, expiry);

                    double payerReference = integratedPrice(strike, vol, expiry, true);
                    double receiverReference = integratedPrice(strike, vol, expiry, false);
                    assertThat(payer).as("payer at K=%s σ=%s T=%s", strike, vol, expiry)
                            .isCloseTo(payerReference, relative(payerReference));
                    assertThat(receiver).as("receiver at K=%s σ=%s T=%s", strike, vol, expiry)
                            .isCloseTo(receiverReference, relative(receiverReference));
                }
            }
        }
    }

    /**
     * At the money the whole formula collapses to {@code A·σ√T/√(2π)}, which is checkable by hand: with
     * A = 1, σ = 1% and T = 1 the price is 0.01/2.5066... = 0.003989422804014327. A payer and a receiver
     * are worth the same there, which is parity with F = K.
     */
    @Test
    void atTheMoneyMatchesTheHandCheckableClosedForm() {
        double atTheMoney = BachelierModel.payer(1, 0.04, 0.04, 0.01, 1);

        assertThat(atTheMoney).isCloseTo(0.003_989_422_804_014_327, within(1e-15));
        assertThat(atTheMoney).isCloseTo(0.01 / Math.sqrt(2 * Math.PI), within(1e-15));
        assertThat(BachelierModel.receiver(1, 0.04, 0.04, 0.01, 1)).isCloseTo(atTheMoney, within(1e-15));
        // And it scales linearly in the Annuity and in σ√T, which is the whole shape of the normal model.
        assertThat(BachelierModel.payer(ANNUITY, 0.04, 0.04, 0.02, 4))
                .isCloseTo(atTheMoney * ANNUITY * 2 * 2, within(1e-15));
    }

    /** The closed-form vega is the derivative of the price, so a central difference has to agree. */
    @Test
    void closedFormVegaMatchesABumpAndReprice() {
        double bump = 1e-7;
        for (double strike : new double[] {0.0212, 0.0380, 0.0412, 0.0450, 0.0612}) {
            for (double vol : new double[] {0.0040, 0.0095, 0.0300}) {
                for (double expiry : new double[] {1.0 / 12, 1, 10}) {
                    double bumped = (BachelierModel.payer(ANNUITY, FORWARD, strike, vol + bump, expiry)
                            - BachelierModel.payer(ANNUITY, FORWARD, strike, vol - bump, expiry)) / (2 * bump);

                    assertThat(BachelierModel.vega(ANNUITY, FORWARD, strike, vol, expiry))
                            .as("vega at K=%s σ=%s T=%s", strike, vol, expiry)
                            .isCloseTo(bumped, within(1e-6));
                    // A receiver has the same vega: the two differ by A·(F − K), which does not move with σ.
                    assertThat((BachelierModel.receiver(ANNUITY, FORWARD, strike, vol + bump, expiry)
                            - BachelierModel.receiver(ANNUITY, FORWARD, strike, vol - bump, expiry)) / (2 * bump))
                            .isCloseTo(bumped, within(1e-6));
                }
            }
        }
        // Reported per 1bp of Normal Volatility, which is what the Vega on screen means.
        assertThat(BachelierModel.vegaPerBasisPoint(ANNUITY, FORWARD, 0.0412, 0.0095, 1))
                .isCloseTo(BachelierModel.vega(ANNUITY, FORWARD, 0.0412, 0.0095, 1) * 1e-4, within(1e-18));
    }

    /**
     * The state a Position sits in on its Expiry date: no time left, so the option is worth its intrinsic
     * value and nothing more. σ = 0 is the same arithmetic, and neither divides by zero.
     */
    @Test
    void noTimeOrNoVolIsIntrinsicRatherThanADivisionByZero() {
        for (double[] degenerate : new double[][] {{0.0095, 0}, {0, 1}, {0, 0}}) {
            double vol = degenerate[0];
            double expiry = degenerate[1];

            assertThat(BachelierModel.payer(ANNUITY, FORWARD, 0.0350, vol, expiry))
                    .as("in the money payer, σ=%s T=%s", vol, expiry)
                    .isCloseTo(ANNUITY * (FORWARD - 0.0350), within(1e-15));
            assertThat(BachelierModel.payer(ANNUITY, FORWARD, 0.0500, vol, expiry))
                    .as("out of the money payer, σ=%s T=%s", vol, expiry).isZero();
            assertThat(BachelierModel.receiver(ANNUITY, FORWARD, 0.0500, vol, expiry))
                    .as("in the money receiver, σ=%s T=%s", vol, expiry)
                    .isCloseTo(ANNUITY * (0.0500 - FORWARD), within(1e-15));
            assertThat(BachelierModel.receiver(ANNUITY, FORWARD, 0.0350, vol, expiry))
                    .as("out of the money receiver, σ=%s T=%s", vol, expiry).isZero();
            assertThat(BachelierModel.payer(ANNUITY, FORWARD, FORWARD, vol, expiry))
                    .as("at the money, σ=%s T=%s", vol, expiry).isZero();
        }
        // Expiring is not a discontinuity: the price walks down to intrinsic as T goes to zero.
        assertThat(BachelierModel.payer(ANNUITY, FORWARD, 0.0350, 0.0095, 1e-12))
                .isCloseTo(ANNUITY * (FORWARD - 0.0350), within(1e-9));

        // At Expiry there is no time value left to gain, so no vega either.
        assertThat(BachelierModel.vega(ANNUITY, FORWARD, FORWARD, 0.0095, 0)).isZero();
        // At σ = 0 the at-the-money vega is the limit as σ falls, and away from the money it is zero.
        assertThat(BachelierModel.vega(ANNUITY, FORWARD, FORWARD, 0, 1))
                .isCloseTo(ANNUITY / Math.sqrt(2 * Math.PI), within(1e-15));
        assertThat(BachelierModel.vega(ANNUITY, FORWARD, 0.0500, 0, 1)).isZero();
    }

    /**
     * Ten standard deviations out of the money. The two terms of the formula nearly cancel there, so the
     * answer is small — but it is a price, and a price is never negative. A sign error or a cancellation
     * bad enough to matter shows up here as a negative number.
     */
    @Test
    void aDeepOutOfTheMoneyOptionIsSmallAndPositive() {
        double vol = 0.0050;
        double deepOut = FORWARD + 10 * vol;

        double payer = BachelierModel.payer(ANNUITY, FORWARD, deepOut, vol, 1);

        assertThat(payer).isPositive().isLessThan(1e-20);
        assertThat(payer).isCloseTo(integratedPrice(deepOut, vol, 1, true), relative(payer));
        // Symmetrically for the receiver, ten standard deviations the other way.
        assertThat(BachelierModel.receiver(ANNUITY, FORWARD, FORWARD - 10 * vol, vol, 1))
                .isPositive().isLessThan(1e-20);
        // Further out still the price underflows to zero rather than going through it.
        assertThat(BachelierModel.payer(ANNUITY, FORWARD, FORWARD + 100 * vol, vol, 1)).isNotNegative();
    }

    @Test
    void negativeVolOrNegativeTimeIsRefusedRatherThanReturningNaN() {
        assertThatThrownBy(() -> BachelierModel.payer(ANNUITY, FORWARD, 0.04, -0.001, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> BachelierModel.receiver(ANNUITY, FORWARD, 0.04, 0.0095, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Time to Expiry");
    }

    /** Negative rates are where the normal model earns its place: Black-76 cannot price this at all. */
    @Test
    void aNegativeForwardAndANegativeStrikePriceNormally() {
        double payer = BachelierModel.payer(ANNUITY, -0.0050, -0.0075, 0.0095, 1);
        double receiver = BachelierModel.receiver(ANNUITY, -0.0050, -0.0075, 0.0095, 1);

        assertThat(payer).isPositive();
        assertThat(receiver).isPositive();
        assertThat(payer - receiver).isCloseTo(ANNUITY * (-0.0050 - (-0.0075)), within(1e-15));
        assertThat(payer).isCloseTo(integratedPrice(-0.0075, 0.0095, 1, true, -0.0050), relative(payer));
    }

    private static double integratedPrice(double strike, double vol, double expiry, boolean payer) {
        return integratedPrice(strike, vol, expiry, payer, FORWARD);
    }

    /**
     * {@code A·E[max(±(F + σ√T·Z − K), 0)]} by Simpson's rule, which is the definition the closed form is
     * derived from and shares no code with it.
     *
     * <p>The integration runs from the strike outwards rather than across it. The payoff has a kink at
     * {@code z* = (K − F)/(σ√T)}, and Simpson's rule is only second-order accurate across a kink but
     * essentially exact on the smooth, linear side of it — which is the difference between agreeing with
     * the closed form to nine digits and agreeing to fifteen.
     */
    private static double integratedPrice(double strike, double vol, double expiry, boolean payer,
                                          double forward) {
        double sigmaRootT = vol * Math.sqrt(expiry);
        double kink = (strike - forward) / sigmaRootT;
        double tail = 40;
        double lower = payer ? Math.max(kink, -tail) : -tail;
        double upper = payer ? tail : Math.min(kink, tail);
        if (upper <= lower) {
            return 0;
        }
        int intervals = 200_000;
        double step = (upper - lower) / intervals;
        double total = 0;
        for (int i = 0; i <= intervals; i++) {
            double z = lower + i * step;
            double rate = forward + sigmaRootT * z;
            double payoff = payer ? rate - strike : strike - rate;
            double density = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
            int weight = i == 0 || i == intervals ? 1 : (i % 2 == 1 ? 4 : 2);
            total += weight * payoff * density;
        }
        return ANNUITY * total * step / 3;
    }

    /**
     * A tolerance that scales with the price, since these span twenty orders of magnitude. The looseness
     * is spent entirely on the deepest strikes, where the formula's two terms nearly cancel and a few of
     * the sixteen digits go with them; the at- and near-the-money prices agree to the last digit or two.
     */
    private static org.assertj.core.data.Offset<Double> relative(double reference) {
        return within(Math.abs(reference) * 1e-12 + 1e-30);
    }
}
