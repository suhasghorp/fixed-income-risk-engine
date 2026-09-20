package com.fixedincomerisk.market;

import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * The session's Fixings of the floating index: a 3-month term rate, simple and
 * ACT/360, set on its reset date. Seeded at startup for periods already running and appended on each Day
 * Rollover that falls on a reset date. The first rate recorded for a date is kept forever.
 */
public final class FixingHistory {

    /** The floating index's name, for display. */
    public static final String INDEX = "USD-3M";
    private static final int INDEX_TENOR_MONTHS = 3;

    private final Map<LocalDate, Double> rates = new TreeMap<>();

    /**
     * Records the Fixing for {@code resetDate}, unless one is already recorded.
     *
     * @return true if recorded, false if the date already had a Fixing (which is left unchanged)
     */
    public boolean record(LocalDate resetDate, double rate) {
        return rates.putIfAbsent(resetDate, rate) == null;
    }

    public Fixings fixings() {
        return new Fixings(rates);
    }

    /**
     * The index rate for {@code resetDate} implied by {@code currency}'s curve in {@code market}: the simple
     * ACT/360 forward rate from the reset date to three months later. For a reset date before the Valuation
     * Date, as when seeding a period already running at startup, the curve is extended backwards at its
     * short rate, since a curve starting today cannot see the past.
     */
    public static double indexRate(MarketState market, String currency, LocalDate resetDate) {
        LocalDate end = resetDate.plusMonths(INDEX_TENOR_MONTHS);
        double accrual = ChronoUnit.DAYS.between(resetDate, end) / 360.0;
        return (discountFactor(market, currency, resetDate) / discountFactor(market, currency, end) - 1) / accrual;
    }

    private static double discountFactor(MarketState market, String currency, LocalDate date) {
        YieldCurve curve = market.curve(currency);
        double t = YearFractions.act365(market.valuationDate(), date);
        if (t > 0) {
            return curve.discountFactor(t);
        }
        return Math.exp(-curve.zeroRate(1 / 365.0) * t);
    }
}
