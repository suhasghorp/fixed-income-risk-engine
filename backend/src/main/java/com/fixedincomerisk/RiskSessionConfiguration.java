package com.fixedincomerisk;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.CurveCache;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceChoice;
import com.fixedincomerisk.curve.TreasuryCurveSource;
import com.fixedincomerisk.curve.TreasuryWebsiteCurveFetcher;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.MaterialityThresholds;
import com.fixedincomerisk.repricing.RepricingSettings;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                    new CurveCache(cacheFile),
                    new BundledCurveSource());
            case BUNDLED -> new BundledCurveSource();
        };
    }

    @Bean
    RiskSession riskSession(CurveSource curveSource,
                            @Value("${risk.hull-white.a}") double meanReversion,
                            @Value("${risk.hull-white.sigma}") double volatility,
                            @Value("${risk.futures.basis.kappa}") double basisMeanReversion,
                            @Value("${risk.futures.basis.long-run-mean}") double basisLongRunMean,
                            @Value("${risk.futures.basis.eta}") double basisVolatility,
                            @Value("${risk.futures.ctd-switch.intensity}") double ctdSwitchIntensity,
                            @Value("${risk.futures.ctd-switch.basis-jump}") double ctdSwitchJump,
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
                            @Value("${risk.correlation.matrix}") String correlationMatrix,
                            @Value("${risk.simulation.seed:}") String configuredSeed,
                            @Value("${risk.simulation.simulated-time-per-tick}") Duration simulatedTimePerTick,
                            @Value("${risk.simulation.ticks-per-day}") int ticksPerDay,
                            @Value("${risk.simulation.stop-at-tick:0}") long stopAtTick,
                            @Value("${risk.materiality.pillar-zero-rate-bp}") double pillarZeroRateThresholdBp,
                            @Value("${risk.materiality.mark-bp}") double markThresholdBp,
                            @Value("${risk.materiality.credit-index-bp}") double creditIndexThresholdBp,
                            @Value("${risk.materiality.basis-points}") double basisThresholdPoints,
                            @Value("${risk.repricing.min-pillar-exposure}") double minPillarExposure,
                            @Value("${risk.repricing.worker-threads}") int workerThreads) {
        long seed = configuredSeed.isBlank() ? new SecureRandom().nextLong() : Long.parseLong(configuredSeed.trim());
        log.info("Simulation seed {} (set risk.simulation.seed={} to replay this run); {} simulated per tick; "
                        + "Day Rollover every {} ticks", seed, seed, simulatedTimePerTick, ticksPerDay);
        SessionConfig config = new SessionConfig(
                curveSource,
                ReferenceData.fromClasspath(),
                new HullWhiteParameters(meanReversion, volatility),
                new FuturesBasisParameters(basisMeanReversion, basisLongRunMean, basisVolatility, ctdSwitchIntensity,
                        ctdSwitchJump),
                new CreditParameters(systemicMeanReversion, systemicLongRunMeanBp, systemicVolatilityBp,
                        sectorMeanReversion, sectorVolatilityBp, idiosyncraticMeanReversion, idiosyncraticVolatilityBp),
                new CreditEventParameters(creditEventIntensity, creditEventJumpMeanBp, creditEventJumpDecay,
                        migrationProbability, maxNotches, printBurstMultiplier, printBurstDecay,
                        scheduledCreditEvents.isBlank() ? List.of() : Arrays.stream(scheduledCreditEvents.split(","))
                                .map(CreditEventParameters.ScheduledCreditEvent::parse)
                                .toList()),
                CorrelationMatrix.parse(correlationMatrix),
                Pillar.parseList(pillars),
                new SimulationSettings(seed, simulatedTimePerTick, ticksPerDay, stopAtTick),
                new RepricingSettings(new MaterialityThresholds(pillarZeroRateThresholdBp, markThresholdBp, creditIndexThresholdBp,
                        basisThresholdPoints), minPillarExposure, workerThreads));
        return RiskSession.create(config);
    }
}
