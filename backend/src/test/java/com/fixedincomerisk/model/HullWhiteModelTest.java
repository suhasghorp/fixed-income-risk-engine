package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.CurveBootstrapper;
import com.fixedincomerisk.curve.DiscountCurve;
import com.fixedincomerisk.market.YieldCurve;
import org.junit.jupiter.api.Test;

class HullWhiteModelTest {

    private static final HullWhiteParameters PARAMETERS = new HullWhiteParameters(0.05, 0.01);

    @Test
    void reproducesTheBootstrappedCurveAtTimeZero() {
        DiscountCurve bootstrapped = new BundledCurveSource().load().curve();
        HullWhiteModel model = HullWhiteModel.calibrate(bootstrapped, PARAMETERS);

        YieldCurve modelCurve = model.curveAt(0, model.initialShortRate());

        for (double t = 0.01; t <= 40; t += 0.01) {
            assertThat(modelCurve.discountFactor(t)).as("P(0,%s)", t)
                    .isCloseTo(bootstrapped.discountFactor(t), within(1e-12));
        }
    }

    @Test
    void thetaOnAFlatForwardCurveMatchesClosedForm() {
        double rate = 0.04;
        DiscountCurve flat = DiscountCurve.fromZeroRates(new double[] {1, 5, 10, 30}, new double[] {rate, rate, rate, rate});
        HullWhiteModel model = HullWhiteModel.calibrate(flat, PARAMETERS);
        double a = PARAMETERS.meanReversion();
        double sigma = PARAMETERS.volatility();

        for (double t : new double[] {0.5, 1, 5, 12}) {
            double expected = a * rate + sigma * sigma / (2 * a) * (1 - Math.exp(-2 * a * t));
            assertThat(model.theta(t)).as("θ(%s)", t).isCloseTo(expected, within(1e-8));
        }
    }
}
