package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session's FX Fixings: the exchange rate of a pair recorded on its fixing date, which sets an NDF's
 * settlement amount. Appended on each Day Rollover that lands on a fixing date, from that day's FX Spot.
 * The first rate recorded for a (pair, date) is kept forever, so a settlement that has been struck cannot
 * be restruck by a later move in spot.
 */
public final class FxFixingHistory {

    private final Map<FxFixings.Key, Double> rates = new LinkedHashMap<>();

    /**
     * Records the FX Fixing for {@code pair} on {@code fixingDate}, unless one is already recorded.
     *
     * @return true if recorded, false if that (pair, date) already had a Fixing, which is left unchanged
     */
    public boolean record(String pair, LocalDate fixingDate, double rate) {
        if (!(rate > 0)) {
            throw new IllegalArgumentException("An FX Fixing must be positive; got " + rate
                    + " for " + pair + " on " + fixingDate);
        }
        return rates.putIfAbsent(new FxFixings.Key(pair, fixingDate), rate) == null;
    }

    public FxFixings fixings() {
        return new FxFixings(rates);
    }
}
