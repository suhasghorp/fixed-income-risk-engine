package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

/** FX Spot is the engine's only process that does not mean-revert, and the tests say so explicitly. */
class FxSpotSimulatorTest {

    private static final double SPOT = 1.146;
    private static final FxSpotParameters EIGHT_PERCENT = new FxSpotParameters(0.08);
    private static final double DT = 1.0 / 8760;

    /**
     * The defining difference from every other factor in the engine: the variance of log spot grows
     * linearly forever. An Ornstein-Uhlenbeck process saturates at η²/2κ instead, so a ratio of four
     * between t and 4t is the thing a mean-reverting process cannot produce.
     */
    @Test
    void logSpotVarianceGrowsLinearlyWithTimeRatherThanSaturating() {
        double oneYear = varianceOfLogSpotAfter(8760, 11);
        double fourYears = varianceOfLogSpotAfter(4 * 8760, 11);

        assertThat(oneYear).isCloseTo(0.08 * 0.08, within(0.002));
        assertThat(fourYears / oneYear).isCloseTo(4.0, within(0.15));
    }

    /** A displaced spot is not pulled back: there is no level for it to revert to. */
    @Test
    void aDisplacedSpotIsNotPulledBack() {
        FxSpotSimulator displaced = new FxSpotSimulator(SPOT * 1.5, EIGHT_PERCENT);
        double start = displaced.spot();

        for (int i = 0; i < 8760; i++) {
            displaced.advance(DT, 0);
        }

        // With no shocks only the −½σ² term moves it, and it moves away from SPOT, not back towards it.
        assertThat(displaced.spot()).isCloseTo(start * Math.exp(-0.5 * 0.08 * 0.08), within(1e-9));
        assertThat(displaced.spot()).isGreaterThan(SPOT * 1.4);
    }

    /** Equal and opposite shocks give equal and opposite log returns about the drift. */
    @Test
    void logReturnsAreSymmetricInTheShock() {
        FxSpotSimulator up = new FxSpotSimulator(SPOT, EIGHT_PERCENT);
        FxSpotSimulator down = new FxSpotSimulator(SPOT, EIGHT_PERCENT);
        double start = Math.log(SPOT);

        up.advance(DT, 1.5);
        down.advance(DT, -1.5);

        double drift = -0.5 * 0.08 * 0.08 * DT;
        assertThat(up.logSpot() - start - drift).isCloseTo(-(down.logSpot() - start - drift), within(1e-15));
    }

    /** Driftless in spot, not in log spot: the simulation hands the currency no expected return. */
    @Test
    void expectedSpotIsUnchangedAfterAYear() {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(5);
        int paths = 40_000;
        double total = 0;
        for (int p = 0; p < paths; p++) {
            FxSpotSimulator simulator = new FxSpotSimulator(SPOT, EIGHT_PERCENT);
            for (int i = 0; i < 365; i++) {
                simulator.advance(1.0 / 365, random.nextGaussian());
            }
            total += simulator.spot();
        }

        assertThat(total / paths).isCloseTo(SPOT, within(0.005));
    }

    /** Stepping the logarithm, not the level, is what keeps spot positive whatever the shock. */
    @Test
    void spotStaysPositiveUnderAViolentShock() {
        FxSpotSimulator simulator = new FxSpotSimulator(SPOT, new FxSpotParameters(0.5));

        for (int i = 0; i < 100; i++) {
            simulator.advance(0.25, -8);
        }

        assertThat(simulator.spot()).isPositive();
    }

    @Test
    void refusesANonPositiveStartingSpot() {
        assertThatThrownBy(() -> new FxSpotSimulator(0, EIGHT_PERCENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must start positive");
        assertThatThrownBy(() -> new FxSpotParameters(-0.01))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
    }

    private static double varianceOfLogSpotAfter(int hours, long seed) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        int paths = 4_000;
        double[] logs = new double[paths];
        for (int p = 0; p < paths; p++) {
            FxSpotSimulator simulator = new FxSpotSimulator(SPOT, EIGHT_PERCENT);
            for (int i = 0; i < hours; i++) {
                simulator.advance(DT, random.nextGaussian());
            }
            logs[p] = simulator.logSpot();
        }
        double mean = 0;
        for (double v : logs) {
            mean += v;
        }
        mean /= paths;
        double variance = 0;
        for (double v : logs) {
            variance += (v - mean) * (v - mean);
        }
        return variance / paths;
    }
}
