package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        return advance(simulator, random, 0);
    }

    private static List<CtdSwitch> advance(FuturesBasisSimulator simulator, RandomGenerator random, long tick) {
        List<Double> shocks = simulator.state().keySet().stream().map(c -> random.nextGaussian()).toList();
        return simulator.advance(tick, HOUR, shocks, random);
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

    /** A scheduled switch fires at its Tick whatever the draw says, so an article's moment stays put. */
    @Test
    void aScheduledCtdSwitchFiresAtItsTickToItsProxyBond() {
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(12, -0.2, 0.5, 0, 0.15,
                        List.of(FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6@480:CTD3"))),
                contracts());
        RandomGenerator random = random(5);

        for (long tick = 1; tick < 480; tick++) {
            assertThat(advance(simulator, random, tick)).isEmpty();
        }
        List<CtdSwitch> switches = advance(simulator, random, 480);

        assertThat(switches).singleElement().satisfies(fired -> {
            assertThat(fired.contract()).isEqualTo("ZNZ6");
            assertThat(fired.fromIndex()).isZero();
            // CTD3 is the third Proxy Bond, index 2.
            assertThat(fired.toIndex()).isEqualTo(2);
            // Scheduled switches jump the Basis upwards, so the moment does not depend on a coin flip.
            assertThat(fired.basisJump()).isEqualTo(0.15);
        });
        assertThat(simulator.state().get("ZNZ6").proxyIndex()).isEqualTo(2);
        // The unscheduled contract is untouched.
        assertThat(simulator.state().get("ZFZ6").proxyIndex()).isZero();
        assertThat(advance(simulator, random, 481)).isEmpty();
    }

    /** Scheduling one contract does not stop the others being drawn. */
    @Test
    void randomSwitchesContinueForContractsThatAreNotScheduled() {
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(0, -0.2, 0, 1e12, 0.15,
                        List.of(FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6@5:CTD2"))),
                contracts());
        RandomGenerator random = random(9);

        List<CtdSwitch> atTickOne = advance(simulator, random, 1);

        // ZNZ6 has nothing scheduled at tick 1, so both contracts draw and both switch.
        assertThat(atTickOne).extracting(CtdSwitch::contract).containsExactly("ZNZ6", "ZFZ6");
    }

    @Test
    void aScheduledSwitchToTheCurrentProxyBondIsNotASwitch() {
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(12, -0.2, 0, 0, 0.15,
                        List.of(FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6@3:CTD1"))),
                contracts());

        assertThat(advance(simulator, random(1), 3)).isEmpty();
    }

    @Test
    void aScheduledSwitchSpecIsParsedAndValidated() {
        FuturesBasisParameters.ScheduledCtdSwitch parsed =
                FuturesBasisParameters.ScheduledCtdSwitch.parse(" ZNZ6@480:CTD3 ");

        assertThat(parsed.contract()).isEqualTo("ZNZ6");
        assertThat(parsed.tick()).isEqualTo(480);
        assertThat(parsed.proxyIndex()).isEqualTo(2);
        assertThatThrownBy(() -> FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6:480:CTD3"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ZNZ6@480:CTD3");
        assertThatThrownBy(() -> FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6@480:3"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ZNZ6@480:CTD3");
        assertThatThrownBy(() -> FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6@480:CTD0"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("starts at 1");
    }

    /** A schedule naming a Proxy Bond the contract does not have fails loudly rather than silently. */
    @Test
    void aScheduledSwitchBeyondTheContractsProxyBondsFails() {
        FuturesBasisSimulator simulator = new FuturesBasisSimulator(
                new FuturesBasisParameters(12, -0.2, 0, 0, 0.15,
                        List.of(FuturesBasisParameters.ScheduledCtdSwitch.parse("ZNZ6@3:CTD9"))),
                contracts());

        assertThatThrownBy(() -> advance(simulator, random(1), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names Proxy Bond 9, but it has only 3");
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
