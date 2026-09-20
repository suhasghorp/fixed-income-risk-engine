package com.fixedincomerisk.risk;

import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.FxPairs;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * DV01 and Bucketed DV01 by central-difference bump-and-reprice of the model's output zero curves.
 * Generic across Instruments: it only calls {@link Instrument#dirtyValue}, so risk is on dirty value.
 * One currency's curve is bumped at a time; every other curve, and every other factor such as a future's
 * Basis, is held fixed. Results are per unit of notional, in value per basis point, positive for a long
 * bond.
 */
public final class SensitivityCalculator {

    static final double ONE_BP = 1e-4;
    static final double ONE_PERCENT = 0.01;
    /** Forward Points are simulated in pips, so a pip is their natural bump. */
    static final double ONE_PIP = 1;

    private final List<Pillar> pillars;

    public SensitivityCalculator(List<Pillar> pillars) {
        Pillar.validateOrder(pillars);
        this.pillars = List.copyOf(pillars);
    }

    public List<Pillar> pillars() {
        return pillars;
    }

    /**
     * The Instrument's rates risk in every currency the market has a curve for. An Instrument that does
     * not touch a currency simply measures zero there, which is the answer, not an omission.
     */
    public RatesSensitivities ratesSensitivities(Instrument instrument, MarketState market) {
        Map<String, CurveSensitivities> byCurrency = new LinkedHashMap<>();
        for (String currency : market.currencies()) {
            byCurrency.put(currency, curveSensitivities(instrument, market, currency));
        }
        return new RatesSensitivities(byCurrency);
    }

    /** Risk in one currency: that currency's curve bumped, every other curve held fixed. */
    public CurveSensitivities curveSensitivities(Instrument instrument, MarketState market, String currency) {
        double dv01 = bumpAndReprice(instrument, market, currency, t -> ONE_BP);
        double[] bucketed = new double[pillars.size()];
        for (int i = 0; i < pillars.size(); i++) {
            int pillar = i;
            bucketed[i] = bumpAndReprice(instrument, market, currency, t -> ONE_BP * weight(pillar, t));
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

    /**
     * FX Delta: the change in value for a <strong>1% move</strong> in each currency against the Reporting
     * Currency, by bumping that pair's spot and repricing. A pip is too small to be meaningful on a
     * Position sized in millions, and a percentage is comparable across pairs quoted at 1.15 and at 1,388.
     *
     * <p>Reported per currency and never netted: an exposure to the euro and one to the won are different
     * risks. An Instrument that does not touch a pair measures zero there, which is the answer.
     */
    public Map<String, Double> fxDelta(Instrument instrument, MarketState market, FxPairs pairs) {
        Map<String, Double> byCurrency = new LinkedHashMap<>();
        for (FxPair pair : pairs.pairs()) {
            byCurrency.merge(pair.riskCurrency(), fxDelta(instrument, market, pair), Double::sum);
        }
        return byCurrency;
    }

    /** One pair's contribution, from a central difference about a 1% move in its risk currency. */
    public double fxDelta(Instrument instrument, MarketState market, FxPair pair) {
        if (!market.fx().spot().containsKey(pair.pair())) {
            return 0;
        }
        double spot = market.fx().spot(pair.pair());
        double up = instrument.dirtyValue(withSpot(market, pair, pair.spotAfterRiskCurrencyMove(spot, ONE_PERCENT)));
        double down = instrument.dirtyValue(withSpot(market, pair, pair.spotAfterRiskCurrencyMove(spot, -ONE_PERCENT)));
        return (up - down) / 2;
    }

    /**
     * Points delta: the change in value for a one-pip move in an NDF's Forward Points, with spot held
     * fixed — mirroring how a future's DV01 holds its Basis fixed. A deliverable forward has none,
     * because its forward rate is derived from two curves rather than quoted.
     */
    public Map<String, Double> pointsDelta(Instrument instrument, MarketState market, FxPairs pairs) {
        Map<String, Double> byPair = new LinkedHashMap<>();
        for (FxPair pair : pairs.nonDeliverable()) {
            if (market.fx().points().containsKey(pair.pair())) {
                byPair.put(pair.pair(), pointsDelta(instrument, market, pair));
            }
        }
        return byPair;
    }

    /** One pair's points delta, from a central difference about a one-pip move. */
    public double pointsDelta(Instrument instrument, MarketState market, FxPair pair) {
        double points = market.fx().points(pair.pair());
        double up = instrument.dirtyValue(withPoints(market, pair, points + ONE_PIP));
        double down = instrument.dirtyValue(withPoints(market, pair, points - ONE_PIP));
        return (up - down) / 2;
    }

    private static MarketState withSpot(MarketState market, FxPair pair, double spot) {
        return market.withFx(market.fx().withSpot(pair.pair(), spot));
    }

    private static MarketState withPoints(MarketState market, FxPair pair, double points) {
        return market.withFx(market.fx().withPoints(pair.pair(), points));
    }

    /** −(P(z + shift) − P(z − shift)) / 2, written so that no sensitivity yields −0. */
    private static double bumpAndReprice(Instrument instrument, MarketState market, String currency,
                                         DoubleUnaryOperator shift) {
        double up = instrument.dirtyValue(bumped(market, currency, shift));
        double down = instrument.dirtyValue(bumped(market, currency, t -> -shift.applyAsDouble(t)));
        return (down - up) / 2;
    }

    private static MarketState bumped(MarketState market, String currency, DoubleUnaryOperator shift) {
        return market.withCurve(currency, new BumpedCurve(market.curve(currency), shift));
    }
}
