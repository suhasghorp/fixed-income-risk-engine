package com.fixedincomerisk.curve;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** A dated par yield curve, points ordered by tenor. Par yields have to be bootstrapped to discount with. */
public record ParCurve(LocalDate curveDate, List<ParPoint> points) implements PublishedCurve {

    public ParCurve {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("A par curve needs at least one point");
        }
        points = points.stream().sorted(Comparator.comparingDouble(ParPoint::years)).toList();
    }

    @Override
    public List<CurveQuote> quotes() {
        return points.stream().map(p -> new CurveQuote(p.tenor(), p.years(), p.parYield())).toList();
    }

    @Override
    public CurveQuoteKind quoteKind() {
        return CurveQuoteKind.PAR_YIELD;
    }

    @Override
    public DiscountCurve toDiscountCurve() {
        return CurveBootstrapper.bootstrap(this);
    }
}
