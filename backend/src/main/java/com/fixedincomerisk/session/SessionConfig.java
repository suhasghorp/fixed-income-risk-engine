package com.fixedincomerisk.session;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.book.Position;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.FxPairs;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.FxSpotParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.model.NdfPointsParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.RepricingSettings;
import com.fixedincomerisk.simulation.SimulationSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to build a {@link RiskSession}.
 *
 * @param curveSources      one per currency the session simulates, each naming its own currency; their
 *                          order is the order risk is reported in
 * @param reportingCurrency the currency Book values are expressed in, and the curve the chart shows.
 *                          It is <em>not</em> a default for pricing: Instruments name the currency they
 *                          discount in, every time.
 * @param hullWhite         short-rate parameters per currency; sharing one σ would make two curves'
 *                          volatilities identical by construction
 * @param fxPairs           the currency pairs simulated, in reporting order
 * @param fxSpot            FX Spot parameters per pair
 * @param ndfPoints         Forward Points parameters per non-deliverable pair
 */
public record SessionConfig(
        List<CurveSource> curveSources,
        String reportingCurrency,
        ReferenceData referenceData,
        Map<String, HullWhiteParameters> hullWhite,
        FuturesBasisParameters futuresBasis,
        FxPairs fxPairs,
        Map<String, FxSpotParameters> fxSpot,
        Map<String, NdfPointsParameters> ndfPoints,
        CreditParameters credit,
        CreditEventParameters creditEvents,
        CorrelationMatrix correlations,
        List<Pillar> pillars,
        SimulationSettings simulation,
        RepricingSettings repricing) {

    public SessionConfig {
        curveSources = List.copyOf(curveSources);
        hullWhite = Map.copyOf(hullWhite);
        fxSpot = Map.copyOf(fxSpot);
        ndfPoints = Map.copyOf(ndfPoints);
        pillars = List.copyOf(pillars);
        if (curveSources.isEmpty()) {
            throw new IllegalArgumentException("A session needs at least one Curve Source");
        }
        List<String> currencies = curveSources.stream().map(CurveSource::currency).toList();
        if (currencies.stream().distinct().count() != currencies.size()) {
            throw new IllegalArgumentException("Two Curve Sources supply the same currency: " + currencies);
        }
        for (String currency : currencies) {
            if (!hullWhite.containsKey(currency)) {
                throw new IllegalArgumentException("No Hull-White parameters for " + currency
                        + "; configured " + hullWhite.keySet());
            }
        }
        if (!currencies.contains(reportingCurrency)) {
            throw new IllegalArgumentException("The Reporting Currency " + reportingCurrency
                    + " has no Curve Source; configured " + currencies);
        }
        // Every simulated driver needs a named factor of its own. Without this, a second currency's short
        // rate would quietly take the first one's shock: two curves moving in lockstep, looking plausible.
        for (String currency : currencies) {
            requireFactor(correlations, shortRateFactor(currency),
                    "currency " + currency + " has a Curve Source");
        }
        for (FxPair pair : fxPairs.pairs()) {
            requireFactor(correlations, pair.spotShockFactor(), "pair " + pair.pair() + " is simulated");
            if (!fxSpot.containsKey(pair.pair())) {
                throw new IllegalArgumentException("No FX Spot parameters for " + pair.pair()
                        + "; configured " + fxSpot.keySet());
            }
        }
        // A Book Position whose pair is not simulated fails here, not mid-tick with an empty FX market.
        for (Instrument instrument : referenceData.book().positions().stream().map(Position::instrument).toList()) {
            String pair = instrument.fxPair().orElse(null);
            if (pair != null && fxPairs.pairs().stream().noneMatch(p -> p.pair().equals(pair))) {
                throw new IllegalArgumentException("Instrument " + instrument.id() + " is priced from " + pair
                        + ", which refdata/fx-pairs.csv does not configure; configured "
                        + fxPairs.pairs().stream().map(FxPair::pair).toList());
            }
        }
        for (FxPair pair : fxPairs.nonDeliverable()) {
            requireFactor(correlations, pair.pointsShockFactor(),
                    "pair " + pair.pair() + " is non-deliverable");
            if (!ndfPoints.containsKey(pair.pair())) {
                throw new IllegalArgumentException("No Forward Points parameters for " + pair.pair()
                        + "; configured " + ndfPoints.keySet());
            }
        }
    }

    /** The correlated-driver name for one currency's short rate. */
    public static String shortRateFactor(String currency) {
        return "shortRate." + currency;
    }

    private static void requireFactor(CorrelationMatrix correlations, String factor, String because) {
        if (!correlations.has(factor)) {
            throw new IllegalArgumentException("risk.correlation.factors must name '" + factor + "' because "
                    + because + "; it names " + correlations.factors());
        }
    }

    /** The currencies this session simulates, in Curve Source order. */
    public List<String> currencies() {
        return curveSources.stream().map(CurveSource::currency).toList();
    }

    /** Curve Sources by the currency each supplies, in configuration order. */
    public Map<String, CurveSource> curveSourcesByCurrency() {
        Map<String, CurveSource> sources = new LinkedHashMap<>();
        curveSources.forEach(source -> sources.put(source.currency(), source));
        return sources;
    }

    public FxSpotParameters fxSpot(String pair) {
        FxSpotParameters parameters = fxSpot.get(pair);
        if (parameters == null) {
            throw new IllegalArgumentException("No FX Spot parameters for " + pair);
        }
        return parameters;
    }

    public NdfPointsParameters ndfPoints(String pair) {
        NdfPointsParameters parameters = ndfPoints.get(pair);
        if (parameters == null) {
            throw new IllegalArgumentException("No Forward Points parameters for " + pair);
        }
        return parameters;
    }

    public HullWhiteParameters hullWhite(String currency) {
        HullWhiteParameters parameters = hullWhite.get(currency);
        if (parameters == null) {
            throw new IllegalArgumentException("No Hull-White parameters for " + currency);
        }
        return parameters;
    }
}
