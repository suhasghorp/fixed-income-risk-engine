package com.fixedincomerisk.instrument;

import java.time.LocalDate;

/**
 * A scheduled payment per unit of notional.
 *
 * @param amount per unit of notional; positive means received by a long Position
 */
public record CashFlow(LocalDate date, Kind kind, double amount) {

    public enum Kind {
        COUPON,
        REDEMPTION,
        /** A swap's fixed-leg payment; negative for the fixed payer. */
        FIXED_LEG,
        /** A swap's floating-leg payment at the recorded Fixing; negative for the floating payer. */
        FLOATING_LEG
    }
}
