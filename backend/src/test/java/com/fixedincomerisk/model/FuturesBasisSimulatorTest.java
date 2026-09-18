package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.MarketState.FuturesMarket;
import com.fixedincomerisk.model.FuturesBasisSimulator.CtdSwitch;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class FuturesBasisSimulatorTest {

    private static final double HOUR = 1.0 / (365 * 24);

    private static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    private static Map<String, Integer> contracts() {
        Map<String, Integer> contracts = new LinkedHashMap<>();
        contracts.put("ZNZ6", 3);
        contracts.put("ZFZ6", 3);
        return contracts;
    }

    /** Advances with independent Basis shocks, one per contract. */
    private static List<CtdSwitch> advance(FuturesBasisSimulator simulator, RandomGenerator random) {
        List<Double> shocks = simulator.state().keySet().stream().map(c -> random.nextGaussian()).toList();
        return simulator.advance(HOUR, shocks, random);
    }

    @Test
    void startsOnTheFirstProxyBondAtTheLongRunMean() {
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(12, -0.2, 0.5, 24, 0.15), contracts());

        assertThat(simulator.state()).containsEntry("ZNZ6", new FuturesMarket(0, -0.2));
    }

    @Test
    void theBasisMeanRevertsWithTheStationaryOuSpread() {
        // Start far from the mean; over many hourly steps the Basis settles around B̄ with
        // stationary standard deviation η/√(2κ).
        double kappa = 12;
        double eta = 0.5;
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(kappa, -0.2, eta, 0, 0), Map.of("ZNZ6", 2));
        RandomGenerator random = random(7);
        double sum = 0;
        double sumSquares = 0;
        int burnIn = 2_000;
        int steps = 200_000;
        for (int i = 0; i < burnIn + steps; i++) {
            advance(simulator, random);
            if (i >= burnIn) {
                double basis = simulator.state().get("ZNZ6").basis();
                sum += basis;
                sumSquares += basis * basis;
            }
        }
        double mean = sum / steps;
        double stdDev = Math.sqrt(sumSquares / steps - mean * mean);

        assertThat(mean).isCloseTo(-0.2, within(0.02));
        assertThat(stdDev).isCloseTo(eta / Math.sqrt(2 * kappa), within(0.01));
    }

    @Test
    void ctdSwitchesArriveAtTheConfiguredIntensity() {
        double intensity = 24;
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(12, -0.2, 0.5, intensity, 0.15), contracts());
        RandomGenerator random = random(11);
        int switches = 0;
        int hours = 10 * 365 * 24;
        for (int i = 0; i < hours; i++) {
            switches += advance(simulator, random).size();
        }

        // Two contracts over ten years at 24 per year: about 480, Poisson standard deviation about 22.
        assertThat(switches).isBetween(400, 560);
    }

    @Test
    void aCtdSwitchMovesToAnotherProxyBondAndJumpsTheBasis() {
        // No diffusion and a switch on every step, so every Basis change is a jump.
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(0, -0.2, 0, 1e12, 0.15), Map.of("ZNZ6", 3));
        RandomGenerator random = random(3);
        FuturesMarket before = simulator.state().get("ZNZ6");

        for (int i = 0; i < 50; i++) {
            List<CtdSwitch> switches = advance(simulator, random);
            FuturesMarket after = simulator.state().get("ZNZ6");

            assertThat(switches).hasSize(1);
            CtdSwitch ctdSwitch = switches.getFirst();
            assertThat(ctdSwitch.fromIndex()).isEqualTo(before.proxyIndex());
            assertThat(ctdSwitch.toIndex()).isEqualTo(after.proxyIndex()).isNotEqualTo(ctdSwitch.fromIndex()).isBetween(0, 2);
            assertThat(Math.abs(ctdSwitch.basisJump())).isEqualTo(0.15);
            assertThat(after.basis() - before.basis()).isCloseTo(ctdSwitch.basisJump(), within(1e-12));
            before = after;
        }
    }

    @Test
    void noSwitchesAtZeroIntensity() {
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(12, -0.2, 0.5, 0, 0.15), contracts());
        RandomGenerator random = random(5);

        for (int i = 0; i < 10_000; i++) {
            assertThat(advance(simulator, random)).isEmpty();
        }
        assertThat(simulator.state().get("ZNZ6").proxyIndex()).isZero();
    }
}
