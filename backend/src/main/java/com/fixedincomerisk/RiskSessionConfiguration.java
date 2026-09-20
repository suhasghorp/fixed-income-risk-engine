package com.fixedincomerisk;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.BundledEcbCurveSource;
import com.fixedincomerisk.curve.CurveCache;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceChoice;
import com.fixedincomerisk.curve.EcbCurveSource;
import com.fixedincomerisk.curve.EcbCurveSourceChoice;
import com.fixedincomerisk.curve.EcbDataPortalCurveFetcher;
import com.fixedincomerisk.curve.EcbTenors;
import com.fixedincomerisk.curve.TreasuryCurveSource;
import com.fixedincomerisk.curve.TreasuryWebsiteCurveFetcher;
import com.fixedincomerisk.curve.ZeroCurve;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.FxSpotParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.model.NdfPointsParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.MaterialityThresholds;
import com.fixedincomerisk.repricing.RepricingSettings;
import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.FxPairs;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.session.RiskSession;
import com.fixedincomerisk.session.SessionConfig;
import com.fixedincomerisk.simulation.SimulationSettings;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class RiskSessionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RiskSessionConfiguration.class);

    @Bean
    CurveSource curveSource(@Value("${risk.curve.source:treasury}") String source,
                            @Value("${risk.curve.treasury-base-url}") URI treasuryBaseUrl,
                            @Value("${risk.curve.cache-file}") Path cacheFile) {
        return switch (CurveSourceChoice.parse(source)) {
            case TREASURY -> new TreasuryCurveSource(
                    new TreasuryWebsiteCurveFetcher(treasuryBaseUrl, Clock.systemUTC()),
                    CurveCache.forParCurve(cacheFile),
                    new BundledCurveSource());
            case BUNDLED -> new BundledCurveSource();
        };
    }

    /**
     * The EUR Curve Source. The engine is multi-currency: {@code risk.correlation.factors} must name a
     * short-rate factor for every currency with a source, and startup fails naming the missing one.
     */
    @Bean
    CurveSource eurCurveSource(@Value("${risk.curve.eur.source:ecb}") String source,
                               @Value("${risk.curve.ecb-base-url}") URI ecbBaseUrl,
                               @Value("${risk.curve.eur-cache-file}") Path cacheFile) {
        EcbTenors tenors = EcbTenors.fromClasspath();
        CurveSource bundled = new BundledEcbCurveSource(tenors);
        return switch (EcbCurveSourceChoice.parse(source)) {
            case ECB -> new EcbCurveSource(
                    new EcbDataPortalCurveFetcher(ecbBaseUrl, tenors),
                    CurveCache.<ZeroCurve>forZeroCurve(cacheFile, tenors),
                    bundled);
            case BUNDLED -> bundled;
        };
    }

    @Bean
    RiskSession riskSession(List<CurveSource> curveSources,
                            Environment environment,
                            @Value("${risk.reporting-currency}") String reportingCurrency,
                            @Value("${risk.futures.basis.kappa}") double basisMeanReversion,
                            @Value("${risk.futures.basis.long-run-mean}") double basisLongRunMean,
                            @Value("${risk.futures.basis.eta}") double basisVolatility,
                            @Value("${risk.futures.ctd-switch.intensity}") double ctdSwitchIntensity,
                            @Value("${risk.futures.ctd-switch.basis-jump}") double ctdSwitchJump,
                            @Value("${risk.futures.ctd-switch.scheduled:}") String scheduledCtdSwitches,
                            @Value("${risk.credit.systemic.kappa}") double systemicMeanReversion,
                            @Value("${risk.credit.systemic.long-run-mean-bp}") double systemicLongRunMeanBp,
                            @Value("${risk.credit.systemic.sigma-bp}") double systemicVolatilityBp,
                            @Value("${risk.credit.sector.kappa}") double sectorMeanReversion,
                            @Value("${risk.credit.sector.sigma-bp}") double sectorVolatilityBp,
                            @Value("${risk.credit.idiosyncratic.kappa}") double idiosyncraticMeanReversion,
                            @Value("${risk.credit.idiosyncratic.sigma-bp}") double idiosyncraticVolatilityBp,
                            @Value("${risk.credit.events.intensity}") double creditEventIntensity,
                            @Value("${risk.credit.events.jump-mean-bp}") double creditEventJumpMeanBp,
                            @Value("${risk.credit.events.jump-decay}") double creditEventJumpDecay,
                            @Value("${risk.credit.events.migration-probability}") double migrationProbability,
                            @Value("${risk.credit.events.max-notches}") int maxNotches,
                            @Value("${risk.credit.events.print-burst-multiplier}") double printBurstMultiplier,
                            @Value("${risk.credit.events.print-burst-decay}") double printBurstDecay,
                            @Value("${risk.credit.events.scheduled:}") String scheduledCreditEvents,
                            @Value("${risk.pillars}") String pillars,
                            @Value("${risk.correlation.factors}") String correlationFactors,
                            @Value("${risk.correlation.matrix}") String correlationMatrix,
                            @Value("${risk.simulation.seed:}") String configuredSeed,
                            @Value("${risk.simulation.simulated-time-per-tick}") Duration simulatedTimePerTick,
                            @Value("${risk.simulation.ticks-per-day}") int ticksPerDay,
                            @Value("${risk.simulation.stop-at-tick:0}") long stopAtTick,
                            @Value("${risk.materiality.pillar-zero-rate-bp}") double pillarZeroRateThresholdBp,
                            @Value("${risk.materiality.mark-bp}") double markThresholdBp,
                            @Value("${risk.materiality.credit-index-bp}") double creditIndexThresholdBp,
                            @Value("${risk.materiality.basis-points}") double basisThresholdPoints,
                            @Value("${risk.materiality.fx-spot-percent}") double fxSpotThresholdPercent,
                            @Value("${risk.materiality.ndf-points-pips}") double ndfPointsThresholdPips,
                            @Value("${risk.repricing.min-pillar-exposure}") double minPillarExposure,
                            @Value("${risk.repricing.worker-threads}") int workerThreads) {
        long seed = configuredSeed.isBlank() ? new SecureRandom().nextLong() : Long.parseLong(configuredSeed.trim());
        log.info("Simulation seed {} (set risk.simulation.seed={} to replay this run); {} simulated per tick; "
                        + "Day Rollover every {} ticks", seed, seed, simulatedTimePerTick, ticksPerDay);
        FxPairs fxPairs = FxPairs.fromClasspath();
        SessionConfig config = new SessionConfig(
                curveSources,
                reportingCurrency,
                ReferenceData.fromClasspath(),
                hullWhiteByCurrency(curveSources, environment),
                new FuturesBasisParameters(basisMeanReversion, basisLongRunMean, basisVolatility, ctdSwitchIntensity,
                        ctdSwitchJump,
                        scheduledCtdSwitches.isBlank() ? List.of() : Arrays.stream(scheduledCtdSwitches.split(","))
                                .map(FuturesBasisParameters.ScheduledCtdSwitch::parse)
                                .toList()),
                fxPairs,
                fxSpotByPair(fxPairs, environment),
                ndfPointsByPair(fxPairs, environment),
                new CreditParameters(systemicMeanReversion, systemicLongRunMeanBp, systemicVolatilityBp,
                        sectorMeanReversion, sectorVolatilityBp, idiosyncraticMeanReversion, idiosyncraticVolatilityBp),
                new CreditEventParameters(creditEventIntensity, creditEventJumpMeanBp, creditEventJumpDecay,
                        migrationProbability, maxNotches, printBurstMultiplier, printBurstDecay,
                        scheduledCreditEvents.isBlank() ? List.of() : Arrays.stream(scheduledCreditEvents.split(","))
                                .map(CreditEventParameters.ScheduledCreditEvent::parse)
                                .toList()),
                CorrelationMatrix.parse(correlationFactors, correlationMatrix),
                Pillar.parseList(pillars),
                new SimulationSettings(seed, simulatedTimePerTick, ticksPerDay, stopAtTick),
                new RepricingSettings(new MaterialityThresholds(pillarZeroRateThresholdBp, markThresholdBp,
                        creditIndexThresholdBp, basisThresholdPoints, fxSpotThresholdPercent,
                        ndfPointsThresholdPips), minPillarExposure, workerThreads));
        return RiskSession.create(config);
    }

    /** FX Spot parameters per pair, read as {@code risk.fx.spot.<PAIR>.sigma}. */
    private static Map<String, FxSpotParameters> fxSpotByPair(FxPairs pairs, Environment environment) {
        Map<String, FxSpotParameters> parameters = new LinkedHashMap<>();
        for (FxPair pair : pairs.pairs()) {
            parameters.put(pair.pair(),
                    new FxSpotParameters(required(environment, "risk.fx.spot." + pair.pair() + ".sigma")));
        }
        return parameters;
    }

    /**
     * Forward Points parameters per non-deliverable pair, read as
     * {@code risk.fx.points.<PAIR>.{kappa,long-run-mean-pips,eta-pips}}. A deliverable pair has none.
     */
    private static Map<String, NdfPointsParameters> ndfPointsByPair(FxPairs pairs, Environment environment) {
        Map<String, NdfPointsParameters> parameters = new LinkedHashMap<>();
        for (FxPair pair : pairs.nonDeliverable()) {
            String prefix = "risk.fx.points." + pair.pair() + ".";
            parameters.put(pair.pair(), new NdfPointsParameters(
                    required(environment, prefix + "kappa"),
                    required(environment, prefix + "long-run-mean-pips"),
                    required(environment, prefix + "eta-pips")));
        }
        return parameters;
    }

    /**
     * Hull-White parameters per currency, read as {@code risk.hull-white.<CCY>.{a,sigma}}. Each Curve
     * Source names its own currency, so adding one makes its parameters required rather than inherited:
     * sharing a σ would make two curves' volatilities identical by construction.
     */
    private static Map<String, HullWhiteParameters> hullWhiteByCurrency(List<CurveSource> curveSources,
                                                                       Environment environment) {
        Map<String, HullWhiteParameters> parameters = new LinkedHashMap<>();
        for (CurveSource source : curveSources) {
            String prefix = "risk.hull-white." + source.currency() + ".";
            parameters.put(source.currency(), new HullWhiteParameters(
                    required(environment, prefix + "a"), required(environment, prefix + "sigma")));
        }
        return parameters;
    }

    private static double required(Environment environment, String key) {
        Double value = environment.getProperty(key, Double.class);
        if (value == null) {
            throw new IllegalStateException("Missing required property " + key);
        }
        return value;
    }
}
