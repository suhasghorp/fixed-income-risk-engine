package com.fixedincomerisk.curve;

import java.time.LocalDate;
import java.util.List;

/**
 * One currency's starting curve: what its publisher quoted, the discount curve that implies, and where
 * the session got it from.
 *
 * <p>The discount curve is derived once, here, so callers never have to know which currency needs a
 * bootstrap and which does not.
 */
public record CurveSnapshot(PublishedCurve published, DiscountCurve curve, CurveSourceKind source) {

    public static CurveSnapshot of(PublishedCurve published, CurveSourceKind source) {
        return new CurveSnapshot(published, published.toDiscountCurve(), source);
    }

    public LocalDate curveDate() {
        return published.curveDate();
    }

    /** The published points behind this curve, for the chart that shows the real market inputs. */
    public List<CurveQuote> quotes() {
        return published.quotes();
    }

    public CurveQuoteKind quoteKind() {
        return published.quoteKind();
    }
}
