package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

/**
 * The Normal Volatility process. Two things have to hold, and the second is why the process is not simply
 * the futures Basis's: the level mean-reverts, and it is positive however long the run and however low
 * the start, because the OU variable is the logarithm and not the level.
 */
class NormalVolSimulatorTest {

    private static final double HOUR = 1.0 / (365 * 24);
    /** 95bp, as USD 1Mx5Y is configured. */
    private static final double LONG_RUN_MEAN = 0.0095;

    private static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    @Test
    void startsAtItsLongRunMean() {
        NormalVolSimulator simulator = new NormalVolSimulator(new NormalVolParameters(2, 0.6, LONG_RUN_MEAN));

        assertThat(simulator.vol()).isCloseTo(LONG_RUN_MEAN, within(1e-15));
        assertThat(simulator.logVol()).isCloseTo(Math.log(LONG_RUN_MEAN), within(1e-15));
    }

    /**
     * A century of hourly steps from a level a tenth of the mean. A level-OU vol would have diffused
     * through zero long before the end of this; this one reverts to its mean and stays positive. The run
     * is long because the sample mean of an OU process converges at the rate of its own reversion: at
     * κ = 2 the process forgets where it was about twice a year, so ten years is only twenty independent
     * looks at it.
     */
    @Test
    void volMeanRevertsFromALowStartAndIsNeverNonPositive() {
        double kappa = 2;
        double eta = 0.6;
        NormalVolSimulator simulator =
                new NormalVolSimulator(new NormalVolParameters(kappa, eta, LONG_RUN_MEAN), LONG_RUN_MEAN / 10);
        RandomGenerator random = random(42);
        int burnIn = 20_000;
        int steps = 100 * 365 * 24;
        double lowest = Double.MAX_VALUE;
        double sumLogVol = 0;
        double sumSquaredLogVol = 0;
        int sampled = 0;

        for (int step = 0; step < burnIn + steps; step++) {
            simulator.advance(HOUR, random.nextGaussian());
            double vol = simulator.vol();
            assertThat(vol).as("Normal Volatility at step " + step).isGreaterThan(0);
            lowest = Math.min(lowest, vol);
            if (step >= burnIn) {
                sumLogVol += simulator.logVol();
                sumSquaredLogVol += simulator.logVol() * simulator.logVol();
                sampled++;
            }
        }
        double meanLogVol = sumLogVol / sampled;
        double stdDevLogVol = Math.sqrt(sumSquaredLogVol / sampled - meanLogVol * meanLogVol);

        // It came back: log vol settles around ln σ̄ with the stationary OU spread η/√(2κ).
        assertThat(meanLogVol).isCloseTo(Math.log(LONG_RUN_MEAN), within(0.03));
        assertThat(stdDevLogVol).isCloseTo(eta / Math.sqrt(2 * kappa), within(0.02));
        // And it went nowhere near zero, having started at a tenth of the mean.
        assertThat(lowest).isGreaterThan(0).isLessThan(LONG_RUN_MEAN);
    }

    /**
     * The OU variable is log vol, not the level. So the <em>log</em> returns, net of the mean-reverting
     * drift, are the symmetric innovations — and the level's own returns are not, which is the point: a
     * positive shock and a negative one of the same size move the level by different amounts.
     */
    @Test
    void logVolIsTheOuVariableSoLogReturnsAreSymmetricAboutTheDrift() {
        double kappa = 2;
        double eta = 0.6;
        NormalVolParameters parameters = new NormalVolParameters(kappa, eta, LONG_RUN_MEAN);
        double dt = 1.0 / 52;

        NormalVolSimulator up = new NormalVolSimulator(parameters, 0.0120);
        NormalVolSimulator down = new NormalVolSimulator(parameters, 0.0120);
        double drift = kappa * (Math.log(LONG_RUN_MEAN) - Math.log(0.0120)) * dt;
        up.advance(dt, 1.5);
        down.advance(dt, -1.5);

        // Equal and opposite in the logarithm, about the drift...
        assertThat(Math.log(up.vol() / 0.0120) - drift)
                .isCloseTo(-(Math.log(down.vol() / 0.0120) - drift), within(1e-14));
        // ...and not in the level, which is what makes this process different from the futures Basis's.
        assertThat(up.vol() - 0.0120).isNotCloseTo(-(down.vol() - 0.0120), within(1e-6));

        // Over a long sample the drift-adjusted log increments have zero mean and the right spread.
        NormalVolSimulator simulator = new NormalVolSimulator(parameters);
        RandomGenerator random = random(13);
        List<Double> innovations = new ArrayList<>();
        for (int step = 0; step < 200_000; step++) {
            double before = simulator.logVol();
            double expectedDrift = kappa * (Math.log(LONG_RUN_MEAN) - before) * HOUR;
            simulator.advance(HOUR, random.nextGaussian());
            innovations.add((simulator.logVol() - before - expectedDrift) / (eta * Math.sqrt(HOUR)));
        }
        double mean = innovations.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double variance = innovations.stream().mapToDouble(z -> (z - mean) * (z - mean)).average().orElseThrow();

        assertThat(mean).isCloseTo(0, within(0.01));
        assertThat(variance).isCloseTo(1, within(0.02));
        assertThat(innovations.stream().filter(z -> z > 0).count())
                .as("as many up as down").isCloseTo(innovations.size() / 2, within(2_000L));
    }

    @Test
    void aNonPositiveStartingVolOrLongRunMeanIsRefused() {
        assertThatThrownBy(() -> new NormalVolParameters(2, 0.6, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        assertThatThrownBy(() -> new NormalVolSimulator(new NormalVolParameters(2, 0.6, LONG_RUN_MEAN), -0.001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must start positive");
        assertThatThrownBy(() -> new NormalVolParameters(-1, 0.6, LONG_RUN_MEAN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
    }

    /** No vol of vol: the level walks deterministically back to its mean and never overshoots it. */
    @Test
    void withoutVolOfVolTheLevelDecaysToTheMean() {
        NormalVolSimulator simulator =
                new NormalVolSimulator(new NormalVolParameters(2, 0, LONG_RUN_MEAN), LONG_RUN_MEAN / 4);

        for (int step = 0; step < 10 * 365 * 24; step++) {
            simulator.advance(HOUR, 99);
            assertThat(simulator.vol()).isLessThanOrEqualTo(LONG_RUN_MEAN);
        }
        assertThat(simulator.vol()).isCloseTo(LONG_RUN_MEAN, within(1e-6));
    }
}
