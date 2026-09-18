package com.fixedincomerisk.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.instrument.TreasuryBond;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SensitivityCalculatorTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final double FLAT_RATE = 0.04;
    private static final MarketState FLAT_MARKET = new MarketState(VALUATION, t -> Math.exp(-FLAT_RATE * t));

    /** An upward-sloping curve, so the checks don't rely on a flat curve. */
    private static final MarketState SLOPED_MARKET =
            new MarketState(VALUATION, t -> Math.exp(-(0.035 + 0.0008 * t) * t));

    private static final TreasuryBond TWO_YEAR = new TreasuryBond(
            "91282CRH6", "2Y", 0.04125, LocalDate.of(2026, 8, 31), LocalDate.of(2028, 8, 31));
    private static final TreasuryBond TEN_YEAR = new TreasuryBond(
            "91282CRF0", "10Y", 0.04625, LocalDate.of(2026, 8, 15), LocalDate.of(2036, 8, 15));
    private static final TreasuryBond THIRTY_YEAR = new TreasuryBond(
            "912810UW6", "30Y", 0.04750, LocalDate.of(2026, 8, 15), LocalDate.of(2056, 8, 15));

    private final SensitivityCalculator calculator = new SensitivityCalculator(Pillar.DEFAULTS);

    @ParameterizedTest
    @ValueSource(strings = {"2Y", "10Y", "30Y"})
    void dv01IsModifiedDurationTimesDirtyPriceTimesOneBasisPoint(String term) {
        TreasuryBond bond = bond(term);
        double dirty = bond.dirtyValue(FLAT_MARKET);
        // Under a flat continuously compounded curve, a parallel zero-rate bump is a yield bump, and
        // modified duration equals the PV-weighted average time to cash flow.
        double modifiedDuration = pvWeightedTime(bond) / dirty;

        double dv01 = calculator.curveSensitivities(bond, FLAT_MARKET).dv01();

        // The central difference is exact up to a third-order term, ~(duration·1bp)²/6 relative.
        assertThat(dv01).isPositive().isCloseTo(modifiedDuration * dirty * 1e-4, within(1e-5 * dv01));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2Y", "10Y", "30Y"})
    void bucketedDv01sSumToTheParallelDv01(String term) {
        CurveSensitivities risk = calculator.curveSensitivities(bond(term), SLOPED_MARKET);

        assertThat(risk.bucketedSum()).isCloseTo(risk.dv01(), within(1e-5 * risk.dv01()));
    }

    @Test
    void aBondOnlyHasBucketedRiskUpToThePillarAfterItsMaturity() {
        double[] buckets = calculator.curveSensitivities(TWO_YEAR, SLOPED_MARKET).bucketedDv01();

        // Pillars: 3M, 1Y, 2Y, 3Y, 5Y, ... The final cash flow (≈1.97Y) sits just before the 2Y Pillar.
        assertThat(buckets[2]).isGreaterThan(buckets[0]).isGreaterThan(buckets[1]);
        assertThat(buckets[3]).isZero();
        assertThat(buckets[4]).isZero();
        assertThat(buckets[8]).isZero();
    }

    @Test
    void theLongestBondsRiskSitsMostlyInTheLongPillars() {
        double[] buckets = calculator.curveSensitivities(THIRTY_YEAR, SLOPED_MARKET).bucketedDv01();

        assertThat(buckets[8]).isGreaterThan(buckets[6]).isGreaterThan(buckets[2]);
    }

    @Test
    void triangularWeightsPeakAtTheirPillarFadeToNeighboursAndPartitionUnity() {
        // Pillar 4 is 5Y, between 3Y and 7Y.
        assertThat(calculator.weight(4, 5)).isEqualTo(1);
        assertThat(calculator.weight(4, 4)).isCloseTo(0.5, within(1e-15));
        assertThat(calculator.weight(4, 6.5)).isCloseTo(0.25, within(1e-15));
        assertThat(calculator.weight(4, 3)).isZero();
        assertThat(calculator.weight(4, 7)).isZero();
        // Flat beyond the ends.
        assertThat(calculator.weight(0, 0.01)).isEqualTo(1);
        assertThat(calculator.weight(8, 40)).isEqualTo(1);

        for (double t = 0.01; t < 45; t += 0.37) {
            double sum = 0;
            for (int i = 0; i < Pillar.DEFAULTS.size(); i++) {
                sum += calculator.weight(i, t);
            }
            assertThat(sum).as("sum of weights at %s", t).isCloseTo(1, within(1e-12));
        }
    }

    @Test
    void positionRiskIsPerUnitRiskTimesSignedQuantity() {
        CurveSensitivities perUnit = calculator.curveSensitivities(TEN_YEAR, SLOPED_MARKET);

        CurveSensitivities shortPosition = perUnit.scaledBy(-3_000_000);

        assertThat(shortPosition.dv01()).isEqualTo(perUnit.dv01() * -3_000_000);
        assertThat(shortPosition.bucketedDv01()[6]).isEqualTo(perUnit.bucketedDv01()[6] * -3_000_000);
    }

    @Test
    void pillarTenorsAreConfigurable() {
        List<Pillar> pillars = Pillar.parseList("6M, 2y,10Y");

        assertThat(pillars).containsExactly(new Pillar("6M", 0.5), new Pillar("2Y", 2), new Pillar("10Y", 10));
        assertThat(new SensitivityCalculator(pillars).curveSensitivities(TEN_YEAR, FLAT_MARKET).bucketedDv01())
                .hasSize(3);
        assertThatThrownBy(() -> Pillar.parseList("2Y,1Y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pillar.parse("10D")).isInstanceOf(IllegalArgumentException.class);
    }

    private static double pvWeightedTime(TreasuryBond bond) {
        double sum = 0;
        for (LocalDate date : bond.couponDates()) {
            if (date.isAfter(VALUATION)) {
                double t = YearFractions.act365(VALUATION, date);
                double cashFlow = bond.couponRate() / 2 + (date.equals(bond.maturityDate()) ? 1 : 0);
                sum += t * cashFlow * Math.exp(-FLAT_RATE * t);
            }
        }
        return sum;
    }

    private static TreasuryBond bond(String term) {
        return switch (term) {
            case "2Y" -> TWO_YEAR;
            case "10Y" -> TEN_YEAR;
            default -> THIRTY_YEAR;
        };
    }
}
