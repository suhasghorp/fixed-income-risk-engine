package com.fixedincomerisk.risk;

import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * DV01 and Bucketed DV01 by central-difference bump-and-reprice of the model's output zero curve.
 * Generic across Instruments: it only calls {@link Instrument#dirtyValue}, so risk is on dirty value.
 * Only the curve is bumped; every other factor, such as a future's Basis, is held fixed. Results are per
 * unit of notional, in value per basis point, positive for a long bond.
 */
public final class SensitivityCalculator {

    static final double ONE_BP = 1e-4;

    private final List<Pillar> pillars;

    public SensitivityCalculator(List<Pillar> pillars) {
        Pillar.validateOrder(pillars);
        this.pillars = List.copyOf(pillars);
    }

    public List<Pillar> pillars() {
        return pillars;
    }

    public CurveSensitivities curveSensitivities(Instrument instrument, MarketState market) {
        double dv01 = bumpAndReprice(instrument, market, t -> ONE_BP);
        double[] bucketed = new double[pillars.size()];
        for (int i = 0; i < pillars.size(); i++) {
            int pillar = i;
            bucketed[i] = bumpAndReprice(instrument, market, t -> ONE_BP * weight(pillar, t));
        }
        return new CurveSensitivities(dv01, bucketed);
    }

    /**
     * Triangular weight of Pillar {@code i} at tenor {@code t}: 1 at the Pillar, fading linearly to 0 at
     * its neighbours, and flat beyond the first and last Pillars. The weights sum to 1 at every tenor, so
     * the Bucketed DV01s add up (to first order) to the parallel DV01.
     */
    double weight(int i, double t) {
        double here = pillars.get(i).years();
        if (t <= here) {
            if (i == 0) {
                return 1;
            }
            double previous = pillars.get(i - 1).years();
            return t <= previous ? 0 : (t - previous) / (here - previous);
        }
        if (i == pillars.size() - 1) {
            return 1;
        }
        double next = pillars.get(i + 1).years();
        return t >= next ? 0 : (next - t) / (next - here);
    }

    /**
     * CS01: the change in value for a 1bp fall in every issuer Mark, by bumping the Marks ±1bp with the
     * curve held fixed. Zero for Instruments that are not priced off a Mark.
     */
    public double cs01(Instrument instrument, MarketState market) {
        double up = instrument.dirtyValue(market.withMarksShiftedBy(ONE_BP));
        double down = instrument.dirtyValue(market.withMarksShiftedBy(-ONE_BP));
        return (down - up) / 2;
    }

    /** −(P(z + shift) − P(z − shift)) / 2, written so that no sensitivity yields −0. */
    private static double bumpAndReprice(Instrument instrument, MarketState market, DoubleUnaryOperator shift) {
        double up = instrument.dirtyValue(bumped(market, shift));
        double down = instrument.dirtyValue(bumped(market, t -> -shift.applyAsDouble(t)));
        return (down - up) / 2;
    }

    private static MarketState bumped(MarketState market, DoubleUnaryOperator shift) {
        return market.withCurve(new BumpedCurve(market.curve(), shift));
    }
}
