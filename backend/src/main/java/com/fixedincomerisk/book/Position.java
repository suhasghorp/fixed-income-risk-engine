package com.fixedincomerisk.book;

import com.fixedincomerisk.instrument.Instrument;

/**
 * A signed quantity of one Instrument held in a Book.
 *
 * @param quantity signed notional (face amount); negative means short. For an Instrument whose terms set
 *                 the side, such as a swap, it is the notional and must be positive.
 */
public record Position(String positionId, Instrument instrument, double quantity) {

    public Position {
        if (instrument.requiresPositiveQuantity() && !(quantity > 0)) {
            throw new IllegalArgumentException("Position " + positionId + " in " + instrument.id()
                    + " must have a positive quantity (its notional); the side is set by the Instrument, not the sign");
        }
    }
}
