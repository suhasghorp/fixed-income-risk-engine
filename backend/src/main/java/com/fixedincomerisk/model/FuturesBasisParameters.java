package com.fixedincomerisk.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The futures Basis process dB = κ·(B̄ − B)·dt + η·dW, plus CTD Switches. Basis values are
 * in price points per 100 face; time is in years.
 *
 * @param meanReversion       κ, per year
 * @param longRunMean         B̄, in price points; the Basis starts here
 * @param volatility          η, in price points per √year
 * @param ctdSwitchIntensity  λ, the Poisson rate of CTD Switches per contract, per year
 * @param ctdSwitchJump       the size of the Basis jump on a CTD Switch, in price points; its sign is random
 * @param scheduledSwitches   CTD Switches fired at a fixed Tick rather than drawn, so a demo moment does
 *                            not depend on a random draw that moves whenever the stream shifts
 */
public record FuturesBasisParameters(
        double meanReversion,
        double longRunMean,
        double volatility,
        double ctdSwitchIntensity,
        double ctdSwitchJump,
        List<ScheduledCtdSwitch> scheduledSwitches) {

    public FuturesBasisParameters {
        if (meanReversion < 0 || volatility < 0 || ctdSwitchIntensity < 0 || ctdSwitchJump < 0) {
            throw new IllegalArgumentException("Basis parameters must be non-negative (except the long-run mean)");
        }
        scheduledSwitches = List.copyOf(scheduledSwitches);
    }

    /** Parameters with no scheduled switches: every CTD Switch is drawn. */
    public FuturesBasisParameters(double meanReversion, double longRunMean, double volatility,
                                  double ctdSwitchIntensity, double ctdSwitchJump) {
        this(meanReversion, longRunMean, volatility, ctdSwitchIntensity, ctdSwitchJump, List.of());
    }

    /** The switch scheduled for {@code contract} at {@code tick}, if any. */
    public Optional<ScheduledCtdSwitch> scheduledAt(String contract, long tick) {
        return scheduledSwitches.stream()
                .filter(scheduled -> scheduled.contract().equals(contract) && scheduled.tick() == tick)
                .findFirst();
    }

    /**
     * A CTD Switch fired at a fixed Tick. Article 6's demo moment is currently a random draw and moves
     * whenever the random stream shifts; scheduling it makes that article stable against re-measurement.
     *
     * @param proxyIndex which Proxy Bond to switch to, zero-based
     */
    public record ScheduledCtdSwitch(String contract, long tick, int proxyIndex) {

        /** Parses {@code CONTRACT@tick:CTDn}, e.g. {@code ZNZ6@480:CTD3}, where CTD1 is the first Proxy Bond. */
        public static ScheduledCtdSwitch parse(String spec) {
            String[] contractAndRest = spec.trim().split("@");
            String[] parts = contractAndRest.length == 2 ? contractAndRest[1].split(":") : new String[0];
            if (parts.length != 2 || !parts[1].trim().toUpperCase(Locale.ROOT).startsWith("CTD")) {
                throw new IllegalArgumentException(
                        "Scheduled CTD Switch must look like ZNZ6@480:CTD3, got " + spec);
            }
            int ordinal = Integer.parseInt(parts[1].trim().substring(3));
            if (ordinal < 1) {
                throw new IllegalArgumentException("CTD ordinal starts at 1, got " + spec);
            }
            return new ScheduledCtdSwitch(contractAndRest[0].trim(), Long.parseLong(parts[0].trim()), ordinal - 1);
        }
    }
}
