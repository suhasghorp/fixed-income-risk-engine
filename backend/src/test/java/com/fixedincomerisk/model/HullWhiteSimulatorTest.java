package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.CurveBootstrapper;
import com.fixedincomerisk.curve.DiscountCurve;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class HullWhiteSimulatorTest {

    private static final HullWhiteParameters PARAMETERS = new HullWhiteParameters(0.05, 0.01);
    private static final DiscountCurve CURVE = CurveBootstrapper.bootstrap(new BundledCurveSource().load().curve());
    private static final HullWhiteModel MODEL = HullWhiteModel.calibrate(CURVE, PARAMETERS);

    @Test
    void closedFormDiscountFactorMatchesMonteCarloFromADisplacedShortRate() {
        double t = 1.0;
        double maturity = 3.0;
        double shortRate = CURVE.instantaneousForward(t) + 0.015;
        int paths = 10_000;
        double dt = 1.0 / 250;
        int steps = (int) Math.round((maturity - t) / dt);
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(7);

        double sum = 0;
        double sumSquares = 0;
        for (int path = 0; path < paths; path++) {
            HullWhiteSimulator simulator = new HullWhiteSimulator(MODEL, t, shortRate);
            double integral = 0;
            for (int step = 0; step < steps; step++) {
                double before = simulator.shortRate();
                simulator.advance(dt, random.nextGaussian());
                integral += 0.5 * (before + simulator.shortRate()) * dt;
            }
            double discount = Math.exp(-integral);
            sum += discount;
            sumSquares += discount * discount;
        }
        double mean = sum / paths;
        double standardError = Math.sqrt((sumSquares / paths - mean * mean) / paths);

        double closedForm = MODEL.discountFactor(t, maturity, shortRate);
        assertThat(mean).isCloseTo(closedForm, within(4 * standardError + 1e-4));
    }

    @Test
    void shortRateRevertsTowardsTheCalibratedMeanPath() {
        double displacement = 0.05;
        double horizon = 5.0;
        double dt = 0.01;
        int steps = (int) Math.round(horizon / dt);
        int paths = 4_000;
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(11);

        double sum = 0;
        double sumSquares = 0;
        for (int path = 0; path < paths; path++) {
            HullWhiteSimulator simulator = new HullWhiteSimulator(MODEL, 0, MODEL.initialShortRate() + displacement);
            for (int step = 0; step < steps; step++) {
                simulator.advance(dt, random.nextGaussian());
            }
            sum += simulator.shortRate();
            sumSquares += simulator.shortRate() * simulator.shortRate();
        }
        double mean = sum / paths;
        double standardError = Math.sqrt((sumSquares / paths - mean * mean) / paths);

        // E[r(t)] = α(t) + δ·e^(−at), with α(t) = f(0,t) + σ²/(2a²)·(1 − e^(−at))².
        double a = PARAMETERS.meanReversion();
        double sigma = PARAMETERS.volatility();
        double alpha = CURVE.instantaneousForward(horizon)
                + sigma * sigma / (2 * a * a) * Math.pow(1 - Math.exp(-a * horizon), 2);
        double expected = alpha + displacement * Math.exp(-a * horizon);

        assertThat(mean).isCloseTo(expected, within(4 * standardError + 5e-4));
        assertThat(mean - alpha).isLessThan(displacement * 0.85);
    }

    @Test
    void zeroShockAtTheInitialStateKeepsTheModelOnItsForwardCurve() {
        HullWhiteSimulator simulator = new HullWhiteSimulator(MODEL);

        simulator.advance(1.0 / 365, 0.0);

        assertThat(simulator.time()).isCloseTo(1.0 / 365, within(1e-15));
        assertThat(simulator.shortRate()).isCloseTo(CURVE.instantaneousForward(1.0 / 365), within(2e-5));
    }
}
