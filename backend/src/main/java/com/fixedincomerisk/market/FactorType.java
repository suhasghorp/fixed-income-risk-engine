package com.fixedincomerisk.market;

/**
 * A kind of Risk Factor. Materiality Thresholds and Staleness are set and reported per factor type, in
 * the type's display unit.
 */
public enum FactorType {

    /** A continuously compounded zero rate at a Pillar of a currency's curve. Reported in basis points. */
    PILLAR_ZERO_RATE("bp", 1e4, false),

    /** An issuer's Mark: the flat Z-spread corporate bonds are priced with. Reported in basis points. */
    MARK("bp", 1e4, false),

    /** The Systemic credit Factor, an observable index level. Reported in basis points. */
    SYSTEMIC("bp", 1e4, false),

    /** A Rating Bucket's Sector Factor, an observable index level. Reported in basis points. */
    SECTOR("bp", 1e4, false),

    /** An issuer's public Rating Bucket. Any change (a Rating Migration) is a move. */
    RATING("", 1, true),

    /** A Treasury future's Basis, in price points per 100 face. */
    BASIS("pts", 1, false),

    /** Which of a future's Proxy Bonds is current, as an index. Any change (a CTD Switch) is a move. */
    PROXY_BOND("", 1, true),

    /**
     * A currency pair's FX Spot, held as its <em>logarithm</em>, so a difference in it is a relative move
     * and its Materiality Threshold is a percentage. A pip is too small to be meaningful on a Position
     * sized in millions, and a percentage is comparable across pairs quoted at 1.15 and at 1,388.
     */
    FX_SPOT("%", 100, false),

    /** An NDF pair's Forward Points, in pips. A deliverable pair has none: its forward comes from curves. */
    NDF_POINTS("pips", 1, false),

    /** The Valuation Date, as an epoch day. Any change is a move: Day Rollover dirties everything. */
    VALUATION_DATE("days", 1, true);

    private final String unit;
    private final double unitsPerValue;
    private final boolean anyChangeIsMove;

    FactorType(String unit, double unitsPerValue, boolean anyChangeIsMove) {
        this.unit = unit;
        this.unitsPerValue = unitsPerValue;
        this.anyChangeIsMove = anyChangeIsMove;
    }

    /**
     * True for discrete factors with no Materiality Threshold: any change reprices every dependent
     * Instrument, so they are never stale.
     */
    public boolean anyChangeIsMove() {
        return anyChangeIsMove;
    }

    public String unit() {
        return unit;
    }

    /** Converts a raw factor value difference into this type's display unit. */
    public double inUnits(double valueDifference) {
        return valueDifference * unitsPerValue;
    }
}
