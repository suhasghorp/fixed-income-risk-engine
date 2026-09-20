package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.risk.SensitivityCalculator;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorporateBondTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final double FLAT_RATE = 0.04;
    private static final double MARK = 0.0110;

    private static final CorporateBond ACME = new CorporateBond(
            "ACME-4.85-2031", "ACME", "Acme Industries", 0.0485, LocalDate.of(2024, 6, 15), LocalDate.of(2031, 6, 15));

    private static MarketState market(double mark) {
        return new MarketState(VALUATION, Map.of("USD", t -> Math.exp(-FLAT_RATE * t)), Map.of(), new MarketState.CreditMarket(
                0.0060, Map.of("A Industrials", 0.0025), Map.of("ACME", "A Industrials"), Map.of("ACME", mark)));
    }

    @Test
    void discountsEachCashFlowOffTheCurvePlusTheIssuersMark() {
        double expected = 0;
        for (CashFlow cashFlow : ACME.cashFlows()) {
            if (cashFlow.date().isAfter(VALUATION)) {
                double t = YearFractions.act365(VALUATION, cashFlow.date());
                expected += cashFlow.amount() * Math.exp(-(FLAT_RATE + MARK) * t);
            }
        }

        assertThat(ACME.dirtyValue(market(MARK))).isCloseTo(expected, within(1e-14));
        assertThat(ACME.dirtyValue(market(MARK + 0.001))).isLessThan(ACME.dirtyValue(market(MARK)));
    }

    @Test
    void accruesOnThirty360() {
        // Last coupon 2026-06-15; 30/360 counts 86 days to 2026-09-11 (15 + 30 + 30 + 11).
        assertThat(YearFractions.days30360(LocalDate.of(2026, 6, 15), VALUATION)).isEqualTo(86);
        assertThat(ACME.accruedInterest(VALUATION)).isCloseTo(0.0485 * 86 / 360, within(1e-15));
        assertThat(ACME.accruedInterest(LocalDate.of(2026, 12, 15))).isZero();
    }

    @Test
    void thirty360TreatsTheThirtyFirstAsTheThirtieth() {
        assertThat(YearFractions.days30360(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31))).isEqualTo(60);
        assertThat(YearFractions.days30360(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 28))).isEqualTo(28);
    }

    @Test
    void cs01IsCloseToSpreadDurationTimesDirtyPriceTimesOneBasisPoint() {
        MarketState market = market(MARK);
        double dirty = ACME.dirtyValue(market);
        double pvWeightedTime = 0;
        for (CashFlow cashFlow : ACME.cashFlows()) {
            if (cashFlow.date().isAfter(VALUATION)) {
                double t = YearFractions.act365(VALUATION, cashFlow.date());
                pvWeightedTime += t * cashFlow.amount() * Math.exp(-(FLAT_RATE + MARK) * t);
            }
        }
        // With a continuously compounded Z-spread, spread duration is the PV-weighted time to cash flow.
        double spreadDuration = pvWeightedTime / dirty;

        double cs01 = new SensitivityCalculator(Pillar.DEFAULTS).cs01(ACME, market);

        assertThat(cs01).isPositive().isCloseTo(spreadDuration * dirty * 1e-4, within(1e-5 * cs01));
    }

    @Test
    void cs01AndDv01AgreeForAFlatCurveAndFlatSpread() {
        // Both shift the same continuously compounded discount rate, so they coincide.
        SensitivityCalculator calculator = new SensitivityCalculator(Pillar.DEFAULTS);

        assertThat(calculator.cs01(ACME, market(MARK)))
                .isCloseTo(calculator.curveSensitivities(ACME, market(MARK), "USD").dv01(), within(1e-12));
    }

    @Test
    void aTreasuryHasNoCs01() {
        TreasuryBond treasury = new TreasuryBond("91282CRH6", "2Y", 0.04125, LocalDate.of(2026, 8, 31),
                LocalDate.of(2028, 8, 31));

        assertThat(new SensitivityCalculator(Pillar.DEFAULTS).cs01(treasury, market(MARK))).isZero();
    }

    @Test
    void declaresItsMarkRatingSystemicAndCurrentSectorFactorsTheValuationDateAndItsPillars() {
        assertThat(ACME.riskFactors(market(MARK), Pillar.DEFAULTS)).contains(
                RiskFactorId.mark("USD", "ACME"),
                RiskFactorId.rating("USD", "ACME"),
                RiskFactorId.systemic("USD"),
                RiskFactorId.sector("USD", "A Industrials"),
                RiskFactorId.valuationDate("USD"),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("5Y")));
        assertThat(ACME.issuer()).contains("ACME");
        assertThat(ACME.description()).isEqualTo("Acme Industries 4.850% 06/15/2031");
    }
}
