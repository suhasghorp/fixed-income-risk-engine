package com.fixedincomerisk.repricing;

import com.fixedincomerisk.market.FactorType;

/**
 * How far a Risk Factor has to move since an Instrument was last priced before that Instrument is
 * repriced, per factor type and in the type's display unit.
 *
 * @param pillarZeroRateBp threshold on a Pillar zero rate, in basis points
 * @param markBp           threshold on an issuer's Mark, in basis points
 * @param creditIndexBp    threshold on the Systemic and Sector Factors, in basis points
 * @param basisPoints      threshold on a futures Basis, in price points
 */
public record MaterialityThresholds(double pillarZeroRateBp, double markBp, double creditIndexBp, double basisPoints) {

    public MaterialityThresholds {
        if (!(pillarZeroRateBp >= 0 && markBp >= 0 && creditIndexBp >= 0 && basisPoints >= 0)) {
            throw new IllegalArgumentException("Materiality Thresholds must be non-negative");
        }
    }

    /** Every move counts: every dependent Instrument reprices on every tick. */
    public static final MaterialityThresholds ZERO = new MaterialityThresholds(0, 0, 0, 0);

    /** The threshold for a factor type. Discrete factors have none: any change is a move. */
    public double threshold(FactorType type) {
        return switch (type) {
            case PILLAR_ZERO_RATE -> pillarZeroRateBp;
            case MARK -> markBp;
            case SYSTEMIC, SECTOR -> creditIndexBp;
            case BASIS -> basisPoints;
            case RATING, PROXY_BOND, VALUATION_DATE -> 0;
        };
    }
}
