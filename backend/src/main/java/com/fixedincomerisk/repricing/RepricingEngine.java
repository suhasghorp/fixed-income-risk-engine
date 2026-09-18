package com.fixedincomerisk.repricing;

import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.RiskFactorId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decides which Instruments to reprice. Owns the Instrument → Risk
 * Factor dependency graph and, per Instrument, the factor values it was last priced at. An Instrument is
 * dirty when some dependency has moved past its factor type's Materiality Threshold since then, so slow
 * drift accumulates until it triggers a reprice.
 *
 * <p>Dependencies can be replaced at any time. A factor an Instrument was not priced against counts as
 * moved, so a new dependency takes effect on the next check.
 *
 * <p>Not thread-safe: callers serialise access.
 */
public final class RepricingEngine {

    private final MaterialityThresholds thresholds;
    private final Map<String, Set<RiskFactorId>> dependencies = new LinkedHashMap<>();
    private final Map<String, Map<RiskFactorId, Double>> pricedAt = new HashMap<>();
    private final Map<String, Long> lastPricedTick = new HashMap<>();

    public RepricingEngine(MaterialityThresholds thresholds) {
        this.thresholds = thresholds;
    }

    public void setDependencies(String instrumentId, Set<RiskFactorId> factors) {
        dependencies.put(instrumentId, Set.copyOf(factors));
    }

    public Set<RiskFactorId> dependencies(String instrumentId) {
        return dependencies.getOrDefault(instrumentId, Set.of());
    }

    /** True if the Instrument was never priced, or a dependency has moved past its threshold since. */
    public boolean isDirty(String instrumentId, MarketState market) {
        Map<RiskFactorId, Double> priced = pricedAt.get(instrumentId);
        if (priced == null) {
            return true;
        }
        for (RiskFactorId factor : dependencies(instrumentId)) {
            Double pricedValue = priced.get(factor);
            if (pricedValue == null || moveInUnits(factor, market, pricedValue) > thresholds.threshold(factor.type())) {
                return true;
            }
        }
        return false;
    }

    /** Remembers the current values of the Instrument's dependencies as the ones it is priced at. */
    public void recordPriced(String instrumentId, MarketState market, long tick) {
        Map<RiskFactorId, Double> values = new HashMap<>();
        for (RiskFactorId factor : dependencies(instrumentId)) {
            values.put(factor, market.riskFactorValue(factor));
        }
        pricedAt.put(instrumentId, values);
        lastPricedTick.put(instrumentId, tick);
    }

    public long lastPricedTick(String instrumentId) {
        Long tick = lastPricedTick.get(instrumentId);
        if (tick == null) {
            throw new IllegalStateException("Instrument " + instrumentId + " has never been priced");
        }
        return tick;
    }

    /**
     * The largest move, per factor type and in its display unit, of any dependency since its Instrument
     * was last priced: how far displayed prices can lag the market. After each cycle's reprices it never
     * exceeds the type's threshold. Covers the factor types some Instrument depends on.
     */
    public Map<FactorType, Double> maxStaleness(MarketState market) {
        Map<FactorType, Double> staleness = new EnumMap<>(FactorType.class);
        dependencies.forEach((instrumentId, factors) -> {
            Map<RiskFactorId, Double> priced = pricedAt.getOrDefault(instrumentId, Map.of());
            for (RiskFactorId factor : factors) {
                Double pricedValue = priced.get(factor);
                if (pricedValue != null) {
                    staleness.merge(factor.type(), moveInUnits(factor, market, pricedValue), Math::max);
                }
            }
        });
        return staleness;
    }

    private static double moveInUnits(RiskFactorId factor, MarketState market, double pricedValue) {
        return Math.abs(factor.type().inUnits(market.riskFactorValue(factor) - pricedValue));
    }
}
