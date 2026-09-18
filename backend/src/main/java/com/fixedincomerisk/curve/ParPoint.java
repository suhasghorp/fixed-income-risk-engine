package com.fixedincomerisk.curve;

/**
 * One tenor of a published par yield curve.
 *
 * @param tenor    the published label, e.g. "2Y"
 * @param years    tenor in years
 * @param parYield par yield as a decimal (0.0463 = 4.63%), semi-annual bond-equivalent basis
 */
public record ParPoint(String tenor, double years, double parYield) {
}
