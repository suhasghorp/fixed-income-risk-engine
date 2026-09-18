package com.fixedincomerisk.simulation;

import java.time.LocalDate;

/**
 * Counts Ticks, the simulated (model) time they add up to, and the Valuation Date. Simulated time has
 * two granularities: every Tick advances model time by dt, while the
 * Valuation Date stays fixed until a Day Rollover every {@code ticksPerDay} Ticks moves it on by one
 * calendar day.
 */
public final class SimulationClock {

    private final double yearsPerTick;
    private final int ticksPerDay;
    private long tick;
    private LocalDate valuationDate;

    public SimulationClock(SimulationSettings settings, LocalDate startDate) {
        this.yearsPerTick = settings.yearsPerTick();
        this.ticksPerDay = settings.ticksPerDay();
        this.valuationDate = startDate;
    }

    /** Advances one Tick, rolling the Valuation Date over when the simulated day is complete. */
    public Step advance() {
        tick++;
        LocalDate previous = valuationDate;
        if (tick % ticksPerDay == 0) {
            valuationDate = valuationDate.plusDays(1);
        }
        return new Step(yearsPerTick, previous, valuationDate);
    }

    public long tick() {
        return tick;
    }

    /** Model time t in years since the session started. */
    public double modelTime() {
        return tick * yearsPerTick;
    }

    public LocalDate valuationDate() {
        return valuationDate;
    }

    /** Ticks still to go before the next Day Rollover, from 1 to {@code ticksPerDay}. */
    public int ticksUntilDayRollover() {
        return ticksPerDay - (int) (tick % ticksPerDay);
    }

    /**
     * One Tick's advance.
     *
     * @param dt                    model time elapsed, in years
     * @param previousValuationDate the Valuation Date before this Tick
     * @param valuationDate         the Valuation Date after this Tick
     */
    public record Step(double dt, LocalDate previousValuationDate, LocalDate valuationDate) {

        public boolean isDayRollover() {
            return !valuationDate.equals(previousValuationDate);
        }
    }
}
