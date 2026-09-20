package com.fixedincomerisk.instrument;

import java.time.LocalDate;

/**
 * A scheduled payment per unit of notional, in a named currency. Most Instruments pay only in the
 * currency they are valued in; a deliverable FX Forward pays two legs in two currencies, which is why the
 * currency is on the cash flow rather than taken from the Instrument.
 *
 * @param amount per unit of notional; positive means received by a long Position
 */
public record CashFlow(LocalDate date, Kind kind, double amount, String currency) {

    public enum Kind {
        COUPON,
        REDEMPTION,
        /** A swap's fixed-leg payment; negative for the fixed payer. */
        FIXED_LEG,
        /** A swap's floating-leg payment at the recorded Fixing; negative for the floating payer. */
        FLOATING_LEG,
        /** One leg of a deliverable FX Forward: both currencies are exchanged in full. */
        FX_LEG,
        /** An NDF's single net settlement, in the settlement currency. */
        FX_SETTLEMENT
    }
}
