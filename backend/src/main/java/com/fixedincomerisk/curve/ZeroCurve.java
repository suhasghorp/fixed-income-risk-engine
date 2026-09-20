package com.fixedincomerisk.curve;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * A dated zero-coupon yield curve, points ordered by tenor. Unlike a {@link ParCurve} this needs no
 * bootstrapping: the publisher has already done it, so the rates go straight into
 * {@link DiscountCurve#fromZeroRates}.
 */
public record ZeroCurve(LocalDate curveDate, List<ZeroPoint> points) implements PublishedCurve {

    public ZeroCurve {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("A zero curve needs at least one point");
        }
        points = points.stream().sorted(Comparator.comparingDouble(ZeroPoint::years)).toList();
    }

    @Override
    public List<CurveQuote> quotes() {
        return points.stream().map(p -> new CurveQuote(p.tenor(), p.years(), p.zeroRate())).toList();
    }

    @Override
    public CurveQuoteKind quoteKind() {
        return CurveQuoteKind.ZERO_RATE;
    }

    @Override
    public DiscountCurve toDiscountCurve() {
        double[] times = new double[points.size()];
        double[] rates = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            times[i] = points.get(i).years();
            rates[i] = points.get(i).zeroRate();
        }
        return DiscountCurve.fromZeroRates(times, rates);
    }
}
