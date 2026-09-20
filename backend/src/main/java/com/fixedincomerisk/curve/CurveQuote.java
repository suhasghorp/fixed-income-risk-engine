package com.fixedincomerisk.curve;

/**
 * One published point of a real curve, as its publisher quotes it.
 *
 * @param tenor the published label, e.g. "10Y"
 * @param years tenor in years
 * @param rate  the quoted rate as a decimal, on the basis {@link CurveQuoteKind} names
 */
public record CurveQuote(String tenor, double years, double rate) {
}
