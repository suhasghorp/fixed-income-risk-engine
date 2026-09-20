package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

/** Forward Points are an OU process in pips, the same shape as the futures Basis. */
class NdfPointsSimulatorTest {

    private static final NdfPointsParameters PARAMETERS = new NdfPointsParameters(12, -150, 40);
    private static final double DT = 1.0 / 8760;

    @Test
    void pointsStartAtTheConfiguredLongRunMean() {
        assertThat(new NdfPointsSimulator(PARAMETERS).points()).isEqualTo(-150);
    }

    /** κ is configurable and is what sets the speed: with no shocks, the gap decays as e^(−κt). */
    @Test
    void aDisplacedLevelDecaysBackTowardsTheMeanAtTheConfiguredSpeed() {
        NdfPointsSimulator fast = new NdfPointsSimulator(new NdfPointsParameters(12, -150, 0), -50);
        NdfPointsSimulator slow = new NdfPointsSimulator(new NdfPointsParameters(3, -150, 0), -50);

        for (int i = 0; i < 8760 / 12; i++) {
            fast.advance(DT, 0);
            slow.advance(DT, 0);
        }

        // One twelfth of a year: the fast process has covered κ·t = 1, the slow one a quarter of that.
        assertThat(fast.points()).isCloseTo(-150 + 100 * Math.exp(-1.0), within(1.0));
        assertThat(slow.points()).isCloseTo(-150 + 100 * Math.exp(-0.25), within(1.0));
        assertThat(fast.points()).isLessThan(slow.points());
    }

    /** Unlike FX Spot, the variance saturates: that is what mean reversion means. */
    @Test
    void varianceSaturatesAtEtaSquaredOverTwoKappa() {
        double oneYear = varianceAfter(8760, 21);
        double fourYears = varianceAfter(4 * 8760, 21);
        double stationary = 40.0 * 40.0 / (2 * 12);

        assertThat(oneYear).isCloseTo(stationary, within(stationary * 0.12));
        assertThat(fourYears).isCloseTo(stationary, within(stationary * 0.12));
        assertThat(fourYears / oneYear).isCloseTo(1.0, within(0.2));
    }

    /** η is configurable and scales the spread of the level, as the Basis process's does. */
    @Test
    void etaScalesTheSpread() {
        double quiet = varianceAfter(8760, 33, new NdfPointsParameters(12, -150, 20));
        double loud = varianceAfter(8760, 33, new NdfPointsParameters(12, -150, 40));

        assertThat(loud / quiet).isCloseTo(4.0, within(0.5));
    }

    @Test
    void kappaAndEtaMustBeNonNegativeButTheMeanMayBeEither() {
        assertThatThrownBy(() -> new NdfPointsParameters(-1, -150, 40))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
        assertThatThrownBy(() -> new NdfPointsParameters(12, -150, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
        assertThat(new NdfPointsParameters(12, -150, 40).longRunMean()).isEqualTo(-150);
        assertThat(new NdfPointsParameters(12, 150, 40).longRunMean()).isEqualTo(150);
    }

    private static double varianceAfter(int hours, long seed) {
        return varianceAfter(hours, seed, PARAMETERS);
    }

    private static double varianceAfter(int hours, long seed, NdfPointsParameters parameters) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        int paths = 4_000;
        double[] levels = new double[paths];
        for (int p = 0; p < paths; p++) {
            NdfPointsSimulator simulator = new NdfPointsSimulator(parameters);
            for (int i = 0; i < hours; i++) {
                simulator.advance(DT, random.nextGaussian());
            }
            levels[p] = simulator.points();
        }
        double mean = 0;
        for (double v : levels) {
            mean += v;
        }
        mean /= paths;
        double variance = 0;
        for (double v : levels) {
            variance += (v - mean) * (v - mean);
        }
        return variance / paths;
    }
}
