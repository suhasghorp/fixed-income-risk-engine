package com.fixedincomerisk.model;

import com.fixedincomerisk.market.MarketState.FuturesMarket;
import com.fixedincomerisk.model.FuturesBasisParameters.ScheduledCtdSwitch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Evolves each futures contract's Basis as an Ornstein-Uhlenbeck process by Euler-Maruyama, and fires
 * CTD Switches as a Poisson process: a switch moves the contract to a different Proxy Bond and jumps the
 * Basis. The diffusion shocks are supplied by the caller (correlated with the short rate);
 * CTD Switches draw from the caller's seeded source, in a fixed order.
 */
public final class FuturesBasisSimulator {

    private final FuturesBasisParameters parameters;
    private final Map<String, Integer> deliverableCounts;
    private final Map<String, FuturesMarket> state = new LinkedHashMap<>();

    /**
     * @param deliverableCounts how many Proxy Bonds each contract can switch between, by contract id, in a
     *                          fixed iteration order
     */
    public FuturesBasisSimulator(FuturesBasisParameters parameters, Map<String, Integer> deliverableCounts) {
        this.parameters = parameters;
        this.deliverableCounts = new LinkedHashMap<>(deliverableCounts);
        deliverableCounts.forEach((contract, count) -> {
            if (count < 2) {
                throw new IllegalArgumentException("Contract " + contract + " needs at least two Proxy Bonds");
            }
            state.put(contract, new FuturesMarket(0, parameters.longRunMean()));
        });
    }

    /**
     * Advances every contract by {@code dt} years and returns the CTD Switches that fired.
     *
     * <p>A contract with a switch scheduled at {@code tick} takes it instead of drawing one: the target
     * Proxy Bond comes from configuration and the Basis jump is positive, so the moment does not move when
     * the random stream shifts. Every contract still draws once for the Poisson trial either way, so
     * scheduling one contract's switch does not change how another contract's draws fall.
     *
     * @param shocks one standard normal Basis shock per contract, in contract order
     */
    public List<CtdSwitch> advance(long tick, double dt, List<Double> shocks, RandomGenerator random) {
        if (shocks.size() != deliverableCounts.size()) {
            throw new IllegalArgumentException("Need one Basis shock per contract, got " + shocks.size());
        }
        double switchProbability = 1 - Math.exp(-parameters.ctdSwitchIntensity() * dt);
        List<CtdSwitch> switches = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Integer> contract : deliverableCounts.entrySet()) {
            FuturesMarket current = state.get(contract.getKey());
            double shock = shocks.get(index++);
            double basis = current.basis()
                    + parameters.meanReversion() * (parameters.longRunMean() - current.basis()) * dt
                    + parameters.volatility() * Math.sqrt(dt) * shock;
            int proxyIndex = current.proxyIndex();
            boolean drawn = random.nextDouble() < switchProbability;
            Optional<ScheduledCtdSwitch> scheduled = parameters.scheduledAt(contract.getKey(), tick);
            if (scheduled.isPresent()) {
                int to = scheduled.get().proxyIndex();
                if (to >= contract.getValue()) {
                    throw new IllegalArgumentException("Scheduled CTD Switch for " + contract.getKey()
                            + " names Proxy Bond " + (to + 1) + ", but it has only " + contract.getValue());
                }
                if (to != proxyIndex) {
                    double jump = parameters.ctdSwitchJump();
                    switches.add(new CtdSwitch(contract.getKey(), proxyIndex, to, jump));
                    proxyIndex = to;
                    basis += jump;
                }
            } else if (drawn) {
                int to = random.nextInt(contract.getValue() - 1);
                to = to >= proxyIndex ? to + 1 : to;
                double jump = random.nextBoolean() ? parameters.ctdSwitchJump() : -parameters.ctdSwitchJump();
                switches.add(new CtdSwitch(contract.getKey(), proxyIndex, to, jump));
                proxyIndex = to;
                basis += jump;
            }
            state.put(contract.getKey(), new FuturesMarket(proxyIndex, basis));
        }
        return switches;
    }

    /** Each contract's current state, by contract id. */
    public Map<String, FuturesMarket> state() {
        return Map.copyOf(state);
    }

    /**
     * @param fromIndex the Proxy Bond before the switch
     * @param toIndex   the Proxy Bond after it
     * @param basisJump the discrete jump applied to the Basis, in price points
     */
    public record CtdSwitch(String contract, int fromIndex, int toIndex, double basisJump) {
    }
}
