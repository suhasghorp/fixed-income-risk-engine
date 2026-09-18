package com.fixedincomerisk.time;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Day-count helpers. */
public final class YearFractions {

    private YearFractions() {
    }

    /** Days between two dates on the 30/360 US (bond basis) convention. */
    public static int days30360(LocalDate from, LocalDate to) {
        int d1 = Math.min(from.getDayOfMonth(), 30);
        int d2 = to.getDayOfMonth();
        if (d1 == 30 && d2 == 31) {
            d2 = 30;
        }
        return 360 * (to.getYear() - from.getYear()) + 30 * (to.getMonthValue() - from.getMonthValue()) + (d2 - d1);
    }

    /** ACT/365 fixed: the model's time axis for discounting. */
    public static double act365(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) / 365.0;
    }
}
