package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Recorded Fixings of the floating index, by reset date: known rates that never change once recorded.
 * An immutable view of the session's {@link FixingHistory}.
 *
 * @param rates simple annual rates (ACT/360), by reset date
 */
public record Fixings(Map<LocalDate, Double> rates) {

    public static final Fixings NONE = new Fixings(Map.of());

    public Fixings {
        rates = Map.copyOf(rates);
    }

    public Optional<Double> on(LocalDate resetDate) {
        return Optional.ofNullable(rates.get(resetDate));
    }

    public double rate(LocalDate resetDate) {
        return on(resetDate).orElseThrow(() -> new IllegalStateException("No Fixing recorded for " + resetDate));
    }
}
