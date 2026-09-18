package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreasuryBondTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final double FLAT_RATE = 0.04;
    private static final MarketState FLAT_MARKET = new MarketState(VALUATION, t -> Math.exp(-FLAT_RATE * t));

    /** 2Y note 91282CRH6: 4.125%, dated 2026-08-31, matures 2028-08-31 (end-of-month roll). */
    private final TreasuryBond twoYear = new TreasuryBond(
            "91282CRH6", "2Y", 0.04125, LocalDate.of(2026, 8, 31), LocalDate.of(2028, 8, 31));

    @Test
    void couponDatesRollBackFromMaturityWithTheEndOfMonthRule() {
        assertThat(twoYear.couponDates()).containsExactly(
                LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 8, 31),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 8, 31));
    }

    @Test
    void accruedInterestIsActualOverActualInTheCouponPeriod() {
        // 11 days accrued (2026-08-31 → 2026-09-11) in a 181-day period (→ 2027-02-28).
        double expected = 0.04125 / 2 * 11.0 / 181.0;

        assertThat(twoYear.accruedInterest(VALUATION)).isCloseTo(expected, within(1e-15));
    }

    @Test
    void dirtyValueMatchesReferenceCashFlowDiscounting() {
        List<LocalDate> couponDates = List.of(
                LocalDate.of(2027, 2, 28), LocalDate.of(2027, 8, 31),
                LocalDate.of(2028, 2, 29), LocalDate.of(2028, 8, 31));
        double expected = 0;
        for (LocalDate date : couponDates) {
            expected += 0.04125 / 2 * Math.exp(-FLAT_RATE * ChronoUnit.DAYS.between(VALUATION, date) / 365.0);
        }
        expected += Math.exp(-FLAT_RATE * ChronoUnit.DAYS.between(VALUATION, LocalDate.of(2028, 8, 31)) / 365.0);

        assertThat(twoYear.dirtyValue(FLAT_MARKET)).isCloseTo(expected, within(1e-14));
        assertThat(twoYear.cleanValue(FLAT_MARKET))
                .isCloseTo(expected - twoYear.accruedInterest(VALUATION), within(1e-14));
    }

    @Test
    void midMonthMaturityRollsOnTheSameDay() {
        TreasuryBond tenYear = new TreasuryBond(
                "91282CRF0", "10Y", 0.04625, LocalDate.of(2026, 8, 15), LocalDate.of(2036, 8, 15));

        assertThat(tenYear.couponDates()).hasSize(20)
                .startsWith(LocalDate.of(2027, 2, 15))
                .endsWith(LocalDate.of(2036, 8, 15));
        // 27 days accrued (08-15 → 09-11) in a 184-day period (→ 2027-02-15).
        assertThat(tenYear.accruedInterest(VALUATION)).isCloseTo(0.04625 / 2 * 27.0 / 184.0, within(1e-15));
    }

    @Test
    void cashFlowsPaidAreThoseAfterTheStartAndOnOrBeforeTheEnd() {
        assertThat(twoYear.cashFlowsPaid(FLAT_MARKET, LocalDate.of(2027, 2, 27), LocalDate.of(2027, 2, 28)))
                .containsExactly(new CashFlow(LocalDate.of(2027, 2, 28), CashFlow.Kind.COUPON, 0.04125 / 2));
        assertThat(twoYear.cashFlowsPaid(FLAT_MARKET, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 1))).isEmpty();
        assertThat(twoYear.cashFlowsPaid(FLAT_MARKET, LocalDate.of(2028, 8, 30), LocalDate.of(2028, 8, 31)))
                .extracting(CashFlow::kind)
                .containsExactly(CashFlow.Kind.COUPON, CashFlow.Kind.REDEMPTION);
    }

    @Test
    void dirtyValueExcludesCashFlowsOnOrBeforeTheValuationDate() {
        MarketState couponDay = new MarketState(LocalDate.of(2027, 2, 28), FLAT_MARKET.curve());
        MarketState dayBefore = new MarketState(LocalDate.of(2027, 2, 27), FLAT_MARKET.curve());

        // The day before, the coupon is one day away and still in the value; on the day it has been paid.
        // On a flat curve every remaining flow is one day's discounting further away the day before.
        double oneDay = Math.exp(-FLAT_RATE / 365);
        assertThat(twoYear.dirtyValue(dayBefore))
                .isCloseTo(oneDay * (0.04125 / 2 + twoYear.dirtyValue(couponDay)), within(1e-14));
        assertThat(twoYear.accruedInterest(couponDay.valuationDate())).isZero();
        assertThat(twoYear.dirtyValue(new MarketState(LocalDate.of(2028, 8, 31), FLAT_MARKET.curve()))).isZero();
    }

    @Test
    void declaresTheValuationDateAndThePillarsAroundItsRemainingCashFlows() {
        assertThat(twoYear.riskFactors(FLAT_MARKET, Pillar.DEFAULTS)).containsExactlyInAnyOrder(
                RiskFactorId.valuationDate("USD"),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("3M")),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("1Y")),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("2Y")));
        // After its last coupon but one, only the Pillars around the final cash flow remain.
        assertThat(twoYear.riskFactors(new MarketState(LocalDate.of(2028, 3, 1), FLAT_MARKET.curve()), Pillar.DEFAULTS)).containsExactlyInAnyOrder(
                RiskFactorId.valuationDate("USD"),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("3M")),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("1Y")));
    }

    @Test
    void describesItselfInMarketStyle() {
        assertThat(twoYear.description()).isEqualTo("UST 4.125% 08/31/2028");
    }
}
