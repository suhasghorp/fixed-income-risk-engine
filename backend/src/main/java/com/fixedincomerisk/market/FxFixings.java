package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recorded FX Fixings, by currency pair and fixing date: exchange rates that never change once recorded.
 * An immutable view of the session's {@link FxFixingHistory}.
 *
 * <p>Distinct from {@link Fixings}, and deliberately not a widening of it. An interest-rate Fixing sets
 * the coupon on a period; an FX Fixing sets a cash settlement. They are recorded from different market
 * data, consumed by different Instruments, and share only the discipline that the first value recorded
 * for a date is the one that stands.
 *
 * @param rates the recorded rate by (pair, date), quoted the pair's own way: quote units per base unit
 */
public record FxFixings(Map<Key, Double> rates) {

    public static final FxFixings NONE = new FxFixings(Map.of());

    /** @param pair the currency pair, e.g. "USDKRW" */
    public record Key(String pair, LocalDate date) {
    }

    public FxFixings {
        rates = Collections.unmodifiableMap(new LinkedHashMap<>(rates));
    }

    public Optional<Double> on(String pair, LocalDate fixingDate) {
        return Optional.ofNullable(rates.get(new Key(pair, fixingDate)));
    }

    /** The recorded rate; fails rather than falling back to the current spot, which would move later. */
    public double rate(String pair, LocalDate fixingDate) {
        return on(pair, fixingDate).orElseThrow(() -> new IllegalStateException(
                "No FX Fixing recorded for " + pair + " on " + fixingDate + "; recorded " + keys()));
    }

    public List<Key> keys() {
        return List.copyOf(rates.keySet());
    }

    public boolean isEmpty() {
        return rates.isEmpty();
    }
}
