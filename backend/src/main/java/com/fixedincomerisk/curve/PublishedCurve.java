package com.fixedincomerisk.curve;

import java.time.LocalDate;
import java.util.List;

/**
 * A dated curve exactly as its publisher quotes it, and the one thing every caller wants from it: the
 * discount curve it defines. How that conversion happens belongs here rather than at the call site,
 * because it differs by publisher — a par curve is bootstrapped, published spot rates are not.
 */
public sealed interface PublishedCurve permits ParCurve, ZeroCurve {

    LocalDate curveDate();

    /** The published points, in tenor order, on the basis {@link #quoteKind()} names. */
    List<CurveQuote> quotes();

    CurveQuoteKind quoteKind();

    DiscountCurve toDiscountCurve();
}
