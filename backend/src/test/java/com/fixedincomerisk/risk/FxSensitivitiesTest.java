package com.fixedincomerisk.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.instrument.FxDirection;
import com.fixedincomerisk.instrument.FxForward;
import com.fixedincomerisk.instrument.FxNdf;
import com.fixedincomerisk.instrument.TreasuryBond;
import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.FxPairs;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.YieldCurve;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FX Delta and the points delta. The sign convention is the interesting part: FX Delta is quoted per
 * <em>currency</em>, but spot moves per <em>pair</em>, and which way spot goes when a currency strengthens
 * depends on which side of the pair it is.
 */
class FxSensitivitiesTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final FxPair EURUSD = new FxPair("EURUSD", "EUR", 1.146, 0.0001, false);
    private static final FxPair USDKRW = new FxPair("USDKRW", "KRW", 1388.0977, 0.01, true);
    private static final FxPairs PAIRS = new FxPairs(List.of(EURUSD, USDKRW));

    private static final MarketState MARKET = market();

    private static MarketState market() {
        YieldCurve usd = t -> Math.exp(-0.04 * t);
        YieldCurve eur = t -> Math.exp(-0.025 * t);
        return new MarketState(VALUATION, Map.of("USD", usd, "EUR", eur))
                .withFx(new MarketState.FxMarket(
                        Map.of("EURUSD", 1.146, "USDKRW", 1388.0977),
                        Map.of("USDKRW", -150.0)));
    }

    private final SensitivityCalculator calculator = new SensitivityCalculator(Pillar.DEFAULTS);

    private final FxForward longEur = new FxForward(
            "FXF", EURUSD, FxDirection.BUY_BASE, 1.15, LocalDate.of(2026, 12, 11));
    /** Short the base (USD) is long the quote (KRW). */
    private final FxNdf longKrw = new FxNdf(
            "NDF", USDKRW, FxDirection.SELL_BASE, 1386.50, LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 13));

    /** EURUSD is USD per EUR, so a stronger euro is spot rising: a EUR buyer gains. */
    @Test
    void aLongBaseCurrencyPositionGainsWhenThatCurrencyStrengthens() {
        Map<String, Double> delta = calculator.fxDelta(longEur, MARKET, PAIRS);

        assertThat(delta.get("EUR")).isPositive();
        assertThat(delta.get("KRW")).isZero();
    }

    /**
     * USDKRW is KRW per USD, so a stronger won is spot <em>falling</em>. Selling the base is being long
     * the won, so this Position gains — and the sign only comes out right if the bump goes the other way.
     */
    @Test
    void aLongQuoteCurrencyPositionGainsWhenThatCurrencyStrengthensEvenThoughSpotFalls() {
        Map<String, Double> delta = calculator.fxDelta(longKrw, MARKET, PAIRS);

        assertThat(delta.get("KRW")).isPositive();
        assertThat(delta.get("EUR")).isZero();
        // The bump really does move spot down for the won.
        assertThat(USDKRW.spotAfterRiskCurrencyMove(1388.0977, 0.01)).isLessThan(1388.0977);
        assertThat(EURUSD.spotAfterRiskCurrencyMove(1.146, 0.01)).isGreaterThan(1.146);
    }

    /** Selling the base instead of buying it flips the sign, and nothing else. */
    @Test
    void theOppositeDirectionIsTheMirrorImage() {
        FxForward shortEur = new FxForward(
                "FXF-S", EURUSD, FxDirection.SELL_BASE, 1.15, LocalDate.of(2026, 12, 11));

        assertThat(calculator.fxDelta(shortEur, MARKET, PAIRS).get("EUR"))
                .isCloseTo(-calculator.fxDelta(longEur, MARKET, PAIRS).get("EUR"), within(1e-12));
    }

    /** A 1% move, not a pip: the delta scales with the size of the bump, and is roughly 1% of value. */
    @Test
    void theDeltaIsForAOnePerCentMoveInTheCurrency() {
        double delta = calculator.fxDelta(longEur, MARKET, EURUSD);
        // The EUR leg is S·P_EUR per unit; 1% of it is what a 1% move is worth.
        double eurLeg = 1.146 * Math.exp(-0.025 * (91 / 365.0));

        assertThat(delta).isCloseTo(0.01 * eurLeg, within(0.001));
    }

    /** The NDF has a points delta; the deliverable outright has none, because its forward is derived. */
    @Test
    void onlyTheNonDeliverableForwardHasAPointsDelta() {
        assertThat(calculator.pointsDelta(longKrw, MARKET, PAIRS))
                .containsOnlyKeys("USDKRW")
                .satisfies(delta -> assertThat(delta.get("USDKRW")).isNotZero());
        assertThat(calculator.pointsDelta(longEur, MARKET, PAIRS).get("USDKRW")).isZero();
    }

    /** Points delta holds spot fixed, the way a future's DV01 holds its Basis fixed. */
    @Test
    void thePointsDeltaIsForOnePipWithSpotHeldFixed() {
        double delta = calculator.pointsDelta(longKrw, MARKET, USDKRW);
        double byHand = (longKrw.dirtyValue(MARKET.withFx(MARKET.fx().withPoints("USDKRW", -149.0)))
                - longKrw.dirtyValue(MARKET.withFx(MARKET.fx().withPoints("USDKRW", -151.0)))) / 2;

        assertThat(delta).isCloseTo(byHand, within(1e-18));
    }

    /** An Instrument that touches no pair measures zero, which is the answer rather than an omission. */
    @Test
    void anInstrumentWithNoFxExposureMeasuresZeroEverywhere() {
        TreasuryBond bond = new TreasuryBond("T", "2Y", 0.04125,
                LocalDate.of(2026, 8, 31), LocalDate.of(2028, 8, 31));

        assertThat(calculator.fxDelta(bond, MARKET, PAIRS)).containsExactlyInAnyOrderEntriesOf(
                Map.of("EUR", 0.0, "KRW", 0.0));
        assertThat(calculator.pointsDelta(bond, MARKET, PAIRS).get("USDKRW")).isZero();
    }
}
