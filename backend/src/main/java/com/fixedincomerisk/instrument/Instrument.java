package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Contract terms only: no quantity. Values are per unit of notional (face). */
public interface Instrument {

    String id();

    InstrumentType type();

    String description();

    /** The currency the Instrument is priced and risk-managed in. */
    String currency();

    /**
     * Every Risk Factor this Instrument's value depends on in {@code market} (its Valuation Date, and for a
     * corporate bond its issuer's public rating), with curve factors given per Pillar. The repricing engine
     * may narrow the curve factors to those with material exposure. The answer can change at runtime, for
     * instance after a Rating Migration.
     */
    Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars);

    /**
     * True for Instruments margined daily, such as futures: a Position's gains and losses are settled
     * every day, so its value is zero even though its price and risk are not.
     */
    default boolean marginedDaily() {
        return false;
    }

    /**
     * Dirty value per unit of notional: cash flows after the Valuation Date only. For an Instrument
     * margined daily, the price per unit whose changes are its gains and losses.
     */
    double dirtyValue(MarketState market);

    /**
     * True if a Position in this Instrument must have a positive quantity, because the Instrument's own
     * terms already say which side the holder is on (a swap pays or receives fixed).
     */
    default boolean requiresPositiveQuantity() {
        return false;
    }

    /** The corporate issuer whose Mark prices this Instrument, if any. */
    default Optional<String> issuer() {
        return Optional.empty();
    }

    /** Accrued interest per unit of notional; zero for Instruments that do not accrue. */
    default double accruedInterest(LocalDate valuationDate) {
        return 0;
    }

    /**
     * Cash flows paid after {@code from} and on or before {@code to}, per unit of notional; {@code market}
     * supplies recorded Fixings. A Day Rollover from {@code from} to {@code to} processes these as lifecycle
     * events; from then on they are no longer in the value.
     */
    List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to);
}
