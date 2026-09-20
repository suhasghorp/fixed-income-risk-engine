package com.fixedincomerisk.curve;

/**
 * One tenor of a published zero-coupon (spot) yield curve.
 *
 * @param tenor    the tenor label, e.g. "10Y"
 * @param years    tenor in years
 * @param zeroRate continuously compounded zero rate as a decimal (0.034875 = 3.4875%)
 */
public record ZeroPoint(String tenor, double years, double zeroRate) {
}
