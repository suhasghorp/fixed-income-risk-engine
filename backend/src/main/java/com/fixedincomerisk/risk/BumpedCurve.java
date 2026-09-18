package com.fixedincomerisk.risk;

import com.fixedincomerisk.market.YieldCurve;
import java.util.function.DoubleUnaryOperator;

/**
 * A curve whose zero rates are shifted by {@code shift(t)}: P'(t) = P(t)·exp(−shift(t)·t). This bumps
 * the model's output curve, not a bond yield and not the short rate: a yield bump only makes sense for
 * bonds, and a short-rate bump moves long tenors less than short ones, so it is not a parallel shift and
 * cannot be bucketed. Bumping the output curve gives one DV01 definition for every curve-sensitive
 * Instrument.
 */
record BumpedCurve(YieldCurve base, DoubleUnaryOperator shift) implements YieldCurve {

    @Override
    public double discountFactor(double timeToCashFlow) {
        return base.discountFactor(timeToCashFlow) * Math.exp(-shift.applyAsDouble(timeToCashFlow) * timeToCashFlow);
    }
}
