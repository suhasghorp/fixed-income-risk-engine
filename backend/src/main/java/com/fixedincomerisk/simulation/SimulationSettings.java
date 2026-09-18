package com.fixedincomerisk.simulation;

import java.time.Duration;

/**
 * @param seed                 the single seed all simulation randomness derives from
 * @param simulatedTimePerTick how much simulated (model) time one Tick advances (not wall-clock time)
 * @param ticksPerDay          how many Ticks make one simulated day: every this many Ticks a Day
 *                             Rollover advances the Valuation Date. Independent of
 *                             {@code simulatedTimePerTick}, so per-tick moves can be made visible
 *                             without ageing Instruments unrealistically fast.
 * @param stopAtTick           the last Tick the simulation advances to, or 0 to run indefinitely; used to
 *                             freeze a reproducible run at an exact state
 */
public record SimulationSettings(long seed, Duration simulatedTimePerTick, int ticksPerDay, long stopAtTick) {

    private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

    public SimulationSettings {
        if (simulatedTimePerTick.isZero() || simulatedTimePerTick.isNegative()) {
            throw new IllegalArgumentException("Simulated time per tick must be positive");
        }
        if (ticksPerDay < 1) {
            throw new IllegalArgumentException("Ticks per simulated day must be at least 1");
        }
        if (stopAtTick < 0) {
            throw new IllegalArgumentException("stop-at-tick must be positive, or 0 for no stop");
        }
    }

    /** Settings that run indefinitely. */
    public SimulationSettings(long seed, Duration simulatedTimePerTick, int ticksPerDay) {
        this(seed, simulatedTimePerTick, ticksPerDay, 0);
    }

    /** True if the simulation may advance beyond {@code tick}. */
    public boolean canAdvancePast(long tick) {
        return stopAtTick == 0 || tick < stopAtTick;
    }

    /** The step dt in years, on the same ACT/365 basis as the model's time axis. */
    public double yearsPerTick() {
        return simulatedTimePerTick.toMillis() / 1000.0 / SECONDS_PER_YEAR;
    }
}
