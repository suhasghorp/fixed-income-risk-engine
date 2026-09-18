package com.fixedincomerisk.market;

/** Discount factors as seen from the Valuation Date. */
@FunctionalInterface
public interface YieldCurve {

    /** Discount factor for a cash flow {@code timeToCashFlow} years (ACT/365) after the Valuation Date. */
    double discountFactor(double timeToCashFlow);

    /** Continuously compounded zero rate. */
    default double zeroRate(double timeToCashFlow) {
        return -Math.log(discountFactor(timeToCashFlow)) / timeToCashFlow;
    }
}
