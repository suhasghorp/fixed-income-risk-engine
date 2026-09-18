package com.fixedincomerisk.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CurveBootstrapperTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 11);

    @Test
    void flatSemiAnnualParCurveGivesFlatContinuousZeroCurve() {
        double y = 0.04;
        List<ParPoint> points = List.of(0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0).stream()
                .map(t -> new ParPoint(t + "Y", t, y))
                .toList();

        DiscountCurve curve = CurveBootstrapper.bootstrap(new ParCurve(DATE, points));

        double expectedZero = 2 * Math.log(1 + y / 2);
        for (double t = 0.5; t <= 30; t += 0.25) {
            assertThat(curve.zeroRate(t)).as("zero rate at %s", t).isCloseTo(expectedZero, within(1e-10));
        }
    }

    @Test
    void shortEndKnotsMatchHandCalculation() {
        ParCurve parCurve = new ParCurve(DATE, List.of(
                new ParPoint("3M", 0.25, 0.040),
                new ParPoint("6M", 0.5, 0.041),
                new ParPoint("1Y", 1.0, 0.042),
                new ParPoint("2Y", 2.0, 0.045)));

        DiscountCurve curve = CurveBootstrapper.bootstrap(parCurve);

        // Bills of at most six months pay (1 + y·T) at maturity.
        double p3m = 1 / (1 + 0.040 * 0.25);
        double p6m = 1 / (1 + 0.041 * 0.5);
        // The 1Y par note pays y/2 at 6M and 1 + y/2 at 1Y.
        double p1y = (1 - 0.021 * p6m) / 1.021;

        assertThat(curve.discountFactor(0.25)).isCloseTo(p3m, within(1e-12));
        assertThat(curve.discountFactor(0.5)).isCloseTo(p6m, within(1e-12));
        assertThat(curve.discountFactor(1.0)).isCloseTo(p1y, within(1e-12));
        assertThat(curve.zeroRate(1.0)).isCloseTo(-Math.log(p1y), within(1e-12));
    }

    @Test
    void everyPublishedParInstrumentRepricesToParOnTheBundledCurve() {
        ParCurve parCurve = new BundledCurveSource().load().curve();

        DiscountCurve curve = CurveBootstrapper.bootstrap(parCurve);

        for (ParPoint point : parCurve.points()) {
            assertThat(independentParValue(point, curve)).as(point.tenor()).isCloseTo(1.0, within(1e-10));
        }
    }

    @Test
    void discountFactorsDecreaseAndForwardsStayPositiveOnTheBundledCurve() {
        DiscountCurve curve = CurveBootstrapper.bootstrap(new BundledCurveSource().load().curve());

        double previous = 1.0;
        for (double t = 0.05; t <= 40; t += 0.05) {
            double df = curve.discountFactor(t);
            assertThat(df).isLessThan(previous);
            assertThat(curve.instantaneousForward(t)).isPositive();
            previous = df;
        }
    }

    /** Written out independently of the bootstrapper's own par valuation. */
    private static double independentParValue(ParPoint point, DiscountCurve curve) {
        double maturity = point.years();
        double y = point.parYield();
        if (maturity <= 0.5 + 1e-9) {
            return (1 + y * maturity) * curve.discountFactor(maturity);
        }
        int coupons = (int) Math.round(maturity * 2);
        double value = curve.discountFactor(maturity);
        for (int i = 0; i < coupons; i++) {
            value += y / 2 * curve.discountFactor(maturity - i * 0.5);
        }
        return value;
    }
}
