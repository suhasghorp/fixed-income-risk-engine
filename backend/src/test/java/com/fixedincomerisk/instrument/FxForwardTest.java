package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.market.YieldCurve;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The deliverable outright, whose forward rate is derived from two curves and spot. */
class FxForwardTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final LocalDate MATURITY = LocalDate.of(2026, 12, 11);
    private static final FxPair EURUSD = new FxPair("EURUSD", "EUR", 1.146, 0.0001, false);
    private static final double SPOT = 1.146;
    private static final double USD_RATE = 0.04;
    private static final double EUR_RATE = 0.025;
    private static final double YEARS = YearFractions.act365(VALUATION, MATURITY);

    private static MarketState market(double spot, double usdRate, double eurRate) {
        return marketOn(VALUATION, spot, usdRate, eurRate);
    }

    private static MarketState marketOn(LocalDate valuationDate, double spot, double usdRate, double eurRate) {
        YieldCurve usd = t -> Math.exp(-usdRate * t);
        YieldCurve eur = t -> Math.exp(-eurRate * t);
        return new MarketState(valuationDate, Map.of("USD", usd, "EUR", eur))
                .withFx(new MarketState.FxMarket(Map.of("EURUSD", spot), Map.of()));
    }

    private static final MarketState MARKET = market(SPOT, USD_RATE, EUR_RATE);

    /** The covered-interest-parity forward: S · P_EUR(T) / P_USD(T). */
    private static double parityForward() {
        return SPOT * Math.exp(-EUR_RATE * YEARS) / Math.exp(-USD_RATE * YEARS);
    }

    private static FxForward struckAt(double rate) {
        return new FxForward("FXF", EURUSD, FxDirection.BUY_BASE, rate, MATURITY);
    }

    /** The anchor: struck at the derived forward, the contract is worth nothing. */
    @Test
    void anOutrightStruckAtTheParityForwardIsWorthZero() {
        assertThat(struckAt(parityForward()).dirtyValue(MARKET)).isCloseTo(0, within(1e-15));
    }

    @Test
    void theDerivedForwardIsTheRateThatMakesItWorthZero() {
        FxForward forward = struckAt(1.15);

        assertThat(forward.forwardRate(MARKET)).isCloseTo(parityForward(), within(1e-15));
        // EUR rates below USD rates, so the euro trades at a forward premium.
        assertThat(forward.forwardRate(MARKET)).isGreaterThan(SPOT);
    }

    @Test
    void valueRespondsCorrectlyToEachInputInTurn() {
        FxForward atTheMoney = struckAt(parityForward());

        // Spot up: the bought base currency is worth more.
        assertThat(atTheMoney.dirtyValue(market(SPOT * 1.01, USD_RATE, EUR_RATE))).isPositive();
        // Quote-currency rates up: the quote leg owed is discounted harder, so the buyer gains.
        assertThat(atTheMoney.dirtyValue(market(SPOT, USD_RATE + 0.001, EUR_RATE))).isPositive();
        // Base-currency rates up: the base leg received is worth less today.
        assertThat(atTheMoney.dirtyValue(market(SPOT, USD_RATE, EUR_RATE + 0.001))).isNegative();
        // Struck above the forward: buying the base currency dear.
        assertThat(struckAt(parityForward() * 1.01).dirtyValue(MARKET)).isNegative();
    }

    @Test
    void sellingTheBaseCurrencyIsTheMirrorImage() {
        double strike = parityForward() * 1.01;
        FxForward buy = struckAt(strike);
        FxForward sell = new FxForward("FXF-S", EURUSD, FxDirection.SELL_BASE, strike, MATURITY);

        assertThat(sell.dirtyValue(MARKET)).isCloseTo(-buy.dirtyValue(MARKET), within(1e-15));
    }

    /** DV01 in both curves and no Forward Points: the mirror image of the NDF. */
    @Test
    void dependsOnBothCurvesSpotAndNoPoints() {
        Pillar threeMonth = Pillar.parse("3M");
        Pillar oneYear = Pillar.parse("1Y");

        assertThat(struckAt(1.15).riskFactors(MARKET, List.of(threeMonth, oneYear))).contains(
                RiskFactorId.valuationDate("USD"),
                RiskFactorId.fxSpot("EUR", "EURUSD"),
                RiskFactorId.pillarZeroRate("USD", threeMonth),
                RiskFactorId.pillarZeroRate("EUR", threeMonth));
        assertThat(struckAt(1.15).riskFactors(MARKET, List.of(threeMonth, oneYear)))
                .noneMatch(factor -> factor.type() == com.fixedincomerisk.market.FactorType.NDF_POINTS);
    }

    @Test
    void isValuedInTheQuoteCurrencyAndStruckOnTheBaseNotional() {
        FxForward forward = struckAt(1.15);

        assertThat(forward.currency()).isEqualTo("USD");
        assertThat(forward.notionalCurrency()).isEqualTo("EUR");
        assertThat(forward.requiresPositiveQuantity()).isTrue();
        assertThat(forward.type()).isEqualTo(InstrumentType.FX_FORWARD);
    }

    /** A matured forward values to zero and stays in the Book, as a matured swap does. */
    @Test
    void valuesToZeroOnceMaturedAndKeepsOnlyTheValuationDateAsADependency() {
        MarketState after = marketOn(MATURITY.plusDays(1), SPOT, USD_RATE, EUR_RATE);

        assertThat(struckAt(1.15).dirtyValue(after)).isZero();
        assertThat(struckAt(1.15).riskFactors(after, Pillar.DEFAULTS))
                .containsExactly(RiskFactorId.valuationDate("USD"));
    }

    /** Both legs settle at maturity, in full, one Lifecycle Event per currency — and only once. */
    @Test
    void bothLegsSettleAtMaturityAsOneEventPerCurrency() {
        assertThat(struckAt(1.15).cashFlowsPaid(MARKET, MATURITY.minusDays(1), MATURITY)).containsExactly(
                new CashFlow(MATURITY, CashFlow.Kind.FX_LEG, 1, "EUR"),
                new CashFlow(MATURITY, CashFlow.Kind.FX_LEG, -1.15, "USD"));
        assertThat(struckAt(1.15).cashFlowsPaid(MARKET, MATURITY, MATURITY.plusDays(30))).isEmpty();
        assertThat(struckAt(1.15).cashFlowsPaid(MARKET, VALUATION, MATURITY.minusDays(1))).isEmpty();
    }

    @Test
    void refusesANonDeliverablePairAndANonPositiveRate() {
        FxPair usdkrw = new FxPair("USDKRW", "KRW", 1388.0977, 0.01, true);

        assertThatThrownBy(() -> new FxForward("X", usdkrw, FxDirection.BUY_BASE, 1386.5, MATURITY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-deliverable");
        assertThatThrownBy(() -> struckAt(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
    }
}
