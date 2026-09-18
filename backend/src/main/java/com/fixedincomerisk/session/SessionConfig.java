package com.fixedincomerisk.session;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.RepricingSettings;
import com.fixedincomerisk.simulation.SimulationSettings;
import java.util.List;

/** Everything needed to build a {@link RiskSession}. */
public record SessionConfig(
        CurveSource curveSource,
        ReferenceData referenceData,
        HullWhiteParameters hullWhite,
        FuturesBasisParameters futuresBasis,
        CreditParameters credit,
        CreditEventParameters creditEvents,
        CorrelationMatrix correlations,
        List<Pillar> pillars,
        SimulationSettings simulation,
        RepricingSettings repricing) {

    public SessionConfig {
        pillars = List.copyOf(pillars);
    }
}
