package com.fixedincomerisk.repricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepricingEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 11);
    private static final RiskFactorId TWO_YEAR = RiskFactorId.pillarZeroRate("USD", Pillar.parse("2Y"));
    private static final RiskFactorId TEN_YEAR = RiskFactorId.pillarZeroRate("USD", Pillar.parse("10Y"));
    private static final RiskFactorId VALUATION_DATE = RiskFactorId.valuationDate("USD");

    private final RepricingEngine engine = new RepricingEngine(new MaterialityThresholds(1, 1, 1, 0.02));

    /** A flat curve at {@code rate}. */
    private static MarketState flat(double rate) {
        return new MarketState(DAY, t -> Math.exp(-rate * t));
    }

    @Test
    void anInstrumentNeverPricedIsDirty() {
        engine.setDependencies("BOND", Set.of(TWO_YEAR));

        assertThat(engine.isDirty("BOND", flat(0.04))).isTrue();
    }

    @Test
    void movesAccumulateSincePricedNotSinceThePreviousCheck() {
        engine.setDependencies("BOND", Set.of(TWO_YEAR, VALUATION_DATE));
        engine.recordPriced("BOND", flat(0.04), 0);

        // Four 0.3bp steps: none past the 1bp threshold alone, but together they are.
        assertThat(engine.isDirty("BOND", flat(0.04003))).isFalse();
        assertThat(engine.isDirty("BOND", flat(0.04006))).isFalse();
        assertThat(engine.isDirty("BOND", flat(0.04009))).isFalse();
        assertThat(engine.maxStaleness(flat(0.04009)).get(FactorType.PILLAR_ZERO_RATE)).isCloseTo(0.9, within(1e-9));
        assertThat(engine.isDirty("BOND", flat(0.04012))).isTrue();
    }

    @Test
    void anyChangeInTheValuationDateIsAMove() {
        engine.setDependencies("BOND", Set.of(TWO_YEAR, VALUATION_DATE));
        engine.recordPriced("BOND", flat(0.04), 0);

        MarketState nextDay = new MarketState(DAY.plusDays(1), flat(0.04).curve());

        assertThat(engine.isDirty("BOND", nextDay)).isTrue();
    }

    @Test
    void dependenciesCanChangeAtRuntimeAndANewOneCountsAsMoved() {
        engine.setDependencies("BOND", Set.of(TWO_YEAR));
        engine.recordPriced("BOND", flat(0.04), 3);
        assertThat(engine.isDirty("BOND", flat(0.04))).isFalse();

        engine.setDependencies("BOND", Set.of(TWO_YEAR, TEN_YEAR));

        assertThat(engine.dependencies("BOND")).containsExactlyInAnyOrder(TWO_YEAR, TEN_YEAR);
        assertThat(engine.isDirty("BOND", flat(0.04))).isTrue();
        engine.recordPriced("BOND", flat(0.04), 7);
        assertThat(engine.isDirty("BOND", flat(0.04))).isFalse();
        assertThat(engine.lastPricedTick("BOND")).isEqualTo(7);
    }

    @Test
    void aFactorNoLongerDependedOnStopsMattering() {
        engine.setDependencies("BOND", Set.of(TWO_YEAR, TEN_YEAR));
        engine.recordPriced("BOND", flat(0.04), 0);
        engine.setDependencies("BOND", Set.of(VALUATION_DATE));

        assertThat(engine.isDirty("BOND", flat(0.05))).as("priced before the Valuation Date was a dependency").isTrue();
        engine.recordPriced("BOND", flat(0.05), 1);
        assertThat(engine.isDirty("BOND", flat(0.06))).isFalse();
    }
}
