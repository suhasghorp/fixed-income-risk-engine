package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.FxFixingHistory;
import com.fixedincomerisk.market.FxFixings;
import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The non-deliverable forward, whose forward rate is quoted as spot plus Forward Points. */
class FxNdfTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final LocalDate FIXING = LocalDate.of(2026, 10, 9);
    private static final LocalDate SETTLEMENT = LocalDate.of(2026, 10, 13);
    private static final FxPair USDKRW = new FxPair("USDKRW", "KRW", 1388.0977, 0.01, true);
    private static final double SPOT = 1388.0977;
    private static final double POINTS = -150;
    private static final double USD_RATE = 0.04;
    private static final double CONTRACT = 1386.50;

    private static MarketState marketOn(LocalDate valuationDate, double spot, double points) {
        return marketOn(valuationDate, spot, points, FxFixings.NONE);
    }

    private static MarketState marketOn(LocalDate valuationDate, double spot, double points, FxFixings fixings) {
        return new MarketState(valuationDate, Map.<String, com.fixedincomerisk.market.YieldCurve>of(
                        "USD", t -> Math.exp(-USD_RATE * t)))
                .withFx(new MarketState.FxMarket(Map.of("USDKRW", spot), Map.of("USDKRW", points), fixings));
    }

    /** A market past the fixing date, with the rate recorded on it. */
    private static MarketState fixedAt(LocalDate valuationDate, double fixedRate, double spotNow) {
        FxFixingHistory history = new FxFixingHistory();
        history.record("USDKRW", FIXING, fixedRate);
        return marketOn(valuationDate, spotNow, POINTS, history.fixings());
    }

    private static final MarketState MARKET = marketOn(VALUATION, SPOT, POINTS);

    private static FxNdf ndf(FxDirection direction) {
        return new FxNdf("NDF", USDKRW, direction, CONTRACT, FIXING, SETTLEMENT);
    }

    /** Spot plus points, with points in pips at the pair's pip size — never derived from a curve. */
    @Test
    void theForwardRateIsQuotedAsSpotPlusPoints() {
        assertThat(ndf(FxDirection.BUY_BASE).forwardRate(MARKET))
                .isCloseTo(SPOT + POINTS * 0.01, within(1e-12));
        assertThat(ndf(FxDirection.BUY_BASE).forwardRate(MARKET)).isLessThan(SPOT);
    }

    /** The settlement is 1 − K/F on the notional, discounted in the settlement currency. */
    @Test
    void valueIsTheDiscountedNetSettlementAgainstTheCurrentForward() {
        double forward = SPOT + POINTS * 0.01;
        double years = YearFractions.act365(VALUATION, SETTLEMENT);
        double expected = (1 - CONTRACT / forward) * Math.exp(-USD_RATE * years);

        assertThat(ndf(FxDirection.BUY_BASE).dirtyValue(MARKET)).isCloseTo(expected, within(1e-15));
        assertThat(ndf(FxDirection.SELL_BASE).dirtyValue(MARKET)).isCloseTo(-expected, within(1e-15));
    }

    @Test
    void struckAtTheCurrentForwardItIsWorthZero() {
        FxNdf atTheMoney = new FxNdf("NDF", USDKRW, FxDirection.BUY_BASE, SPOT + POINTS * 0.01, FIXING, SETTLEMENT);

        assertThat(atTheMoney.dirtyValue(MARKET)).isCloseTo(0, within(1e-15));
    }

    @Test
    void valueRespondsToSpotAndToPointsSeparately() {
        FxNdf buyer = ndf(FxDirection.BUY_BASE);
        double base = buyer.dirtyValue(MARKET);

        // Spot up: the bought base currency fixes higher than contracted, so the buyer gains.
        assertThat(buyer.dirtyValue(marketOn(VALUATION, SPOT * 1.001, POINTS))).isGreaterThan(base);
        // Points up (less negative): the quoted forward rises, likewise.
        assertThat(buyer.dirtyValue(marketOn(VALUATION, SPOT, POINTS + 50))).isGreaterThan(base);
    }

    /** One curve and a points delta: the mirror image of the outright's two curves and no points. */
    @Test
    void dependsOnTheSettlementCurveSpotAndPointsButNoSecondCurve() {
        Pillar threeMonth = Pillar.parse("3M");

        java.util.Set<RiskFactorId> factors = ndf(FxDirection.BUY_BASE)
                .riskFactors(MARKET, List.of(threeMonth, Pillar.parse("1Y")));

        assertThat(factors).contains(
                RiskFactorId.valuationDate("USD"),
                RiskFactorId.fxSpot("KRW", "USDKRW"),
                RiskFactorId.ndfPoints("KRW", "USDKRW"),
                RiskFactorId.pillarZeroRate("USD", threeMonth));
        // There is no KRW curve to depend on, which is the whole reason the points are quoted.
        assertThat(factors).noneMatch(factor -> factor.type() == FactorType.PILLAR_ZERO_RATE
                && factor.currency().equals("KRW"));
    }

    /** Once the rate has fixed, neither spot nor the points move the settlement amount. */
    @Test
    void dropsTheSpotAndPointsDependenciesOnceFixed() {
        MarketState afterFixing = fixedAt(FIXING, 1400.0, SPOT);

        assertThat(ndf(FxDirection.BUY_BASE).riskFactors(afterFixing, Pillar.DEFAULTS))
                .noneMatch(factor -> factor.type() == FactorType.NDF_POINTS
                        || factor.type() == FactorType.FX_SPOT);
        // Still discounted, so the settlement curve is still a dependency.
        assertThat(ndf(FxDirection.BUY_BASE).riskFactors(afterFixing, Pillar.DEFAULTS))
                .anyMatch(factor -> factor.type() == FactorType.PILLAR_ZERO_RATE);
    }

    /** Before the fixing date it values off the quoted forward; from then on, off the recorded Fixing. */
    @Test
    void valuesOffTheForwardBeforeFixingAndOffTheRecordedFixingAfterwards() {
        assertThat(ndf(FxDirection.BUY_BASE).settlementRate(MARKET)).isEqualTo(SPOT + POINTS * 0.01);
        assertThat(ndf(FxDirection.BUY_BASE).settlementRate(fixedAt(FIXING, 1400.0, SPOT))).isEqualTo(1400.0);
    }

    /** The settlement is struck on the Fixing, and a later move in spot does not restrike it. */
    @Test
    void aRecordedFixingIsWhatSettlesEvenAfterSpotMovesAway() {
        FxNdf buyer = ndf(FxDirection.BUY_BASE);
        double onTheDay = buyer.dirtyValue(fixedAt(FIXING, 1400.0, 1400.0));

        // Spot collapses afterwards; the settlement amount does not care.
        double later = buyer.dirtyValue(fixedAt(FIXING.plusDays(2), 1400.0, 1200.0));
        double years = YearFractions.act365(FIXING.plusDays(2), SETTLEMENT);

        assertThat(buyer.settlementPerUnit(fixedAt(FIXING.plusDays(2), 1400.0, 1200.0)))
                .isCloseTo(1 - CONTRACT / 1400.0, within(1e-15));
        assertThat(later).isCloseTo((1 - CONTRACT / 1400.0) * Math.exp(-USD_RATE * years), within(1e-15));
        // Only the discounting differs between the two dates.
        assertThat(later).isGreaterThan(onTheDay);
    }

    /** Falling back to today's spot would silently restrike a settlement that is already struck. */
    @Test
    void refusesToValueAfterFixingWithNoRecordedFixing() {
        MarketState unrecorded = marketOn(FIXING, SPOT, POINTS);

        assertThatThrownBy(() -> ndf(FxDirection.BUY_BASE).dirtyValue(unrecorded))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FX Fixing recorded for USDKRW on 2026-10-09");
    }

    @Test
    void isValuedAndStruckInTheDeliverableCurrency() {
        FxNdf ndf = ndf(FxDirection.BUY_BASE);

        assertThat(ndf.currency()).isEqualTo("USD");
        assertThat(ndf.notionalCurrency()).isEqualTo("USD");
        assertThat(ndf.requiresPositiveQuantity()).isTrue();
        assertThat(ndf.type()).isEqualTo(InstrumentType.FX_NDF);
    }

    @Test
    void valuesToZeroOnceSettledAndKeepsOnlyTheValuationDateAsADependency() {
        MarketState after = fixedAt(SETTLEMENT.plusDays(1), 1400.0, SPOT);

        assertThat(ndf(FxDirection.BUY_BASE).dirtyValue(after)).isZero();
        assertThat(ndf(FxDirection.BUY_BASE).riskFactors(after, Pillar.DEFAULTS))
                .containsExactly(RiskFactorId.valuationDate("USD"));
    }

    /** One net payment, in the settlement currency, appearing exactly once. */
    @Test
    void settlesAsASingleNetPaymentOnTheSettlementDate() {
        MarketState market = fixedAt(SETTLEMENT.minusDays(1), 1400.0, 1200.0);
        List<CashFlow> paid = ndf(FxDirection.BUY_BASE)
                .cashFlowsPaid(market, SETTLEMENT.minusDays(1), SETTLEMENT);

        assertThat(paid).singleElement().satisfies(flow -> {
            assertThat(flow.date()).isEqualTo(SETTLEMENT);
            assertThat(flow.kind()).isEqualTo(CashFlow.Kind.FX_SETTLEMENT);
            assertThat(flow.currency()).isEqualTo("USD");
        });
        assertThat(paid).singleElement().satisfies(flow ->
                assertThat(flow.amount()).isCloseTo(1 - CONTRACT / 1400.0, within(1e-15)));
        assertThat(ndf(FxDirection.BUY_BASE).cashFlowsPaid(market, SETTLEMENT, SETTLEMENT.plusDays(30))).isEmpty();
        assertThat(ndf(FxDirection.BUY_BASE).cashFlowsPaid(MARKET, VALUATION, SETTLEMENT.minusDays(1))).isEmpty();
    }

    @Test
    void refusesADeliverablePairAndASettlementBeforeItsFixing() {
        FxPair eurusd = new FxPair("EURUSD", "EUR", 1.146, 0.0001, false);

        assertThatThrownBy(() -> new FxNdf("X", eurusd, FxDirection.BUY_BASE, 1.15, FIXING, SETTLEMENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deliverable");
        assertThatThrownBy(() -> new FxNdf("X", USDKRW, FxDirection.BUY_BASE, CONTRACT, SETTLEMENT, FIXING))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("on or after the fixing date");
    }
}
