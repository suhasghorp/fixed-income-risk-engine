package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.MarketState.FuturesMarket;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.market.YieldCurve;
import com.fixedincomerisk.risk.CurveSensitivities;
import com.fixedincomerisk.risk.SensitivityCalculator;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TreasuryFutureTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final YieldCurve SLOPED = t -> Math.exp(-(0.035 + 0.0008 * t) * t);

    private static final TreasuryBond CTD1 = new TreasuryBond(
            "ZNZ6-CTD1", "Proxy", 0.04125, LocalDate.of(2023, 8, 15), LocalDate.of(2033, 8, 15));
    private static final TreasuryBond CTD2 = new TreasuryBond(
            "ZNZ6-CTD2", "Proxy", 0.0425, LocalDate.of(2023, 11, 15), LocalDate.of(2033, 11, 15));

    private final TreasuryFuture future = new TreasuryFuture("ZNZ6", "ZN Dec26 10Y note future",
            List.of(new ProxyBond(CTD1, 0.9003), new ProxyBond(CTD2, 0.9040)));

    private static MarketState market(int proxyIndex, double basis) {
        return new MarketState(VALUATION, SLOPED, Map.of("ZNZ6", new FuturesMarket(proxyIndex, basis)),
                MarketState.CreditMarket.NONE);
    }

    @Test
    void priceIsTheProxyBondsCleanPriceOverItsConversionFactorPlusTheBasis() {
        MarketState market = market(0, -0.20);

        double expectedPer100 = CTD1.cleanValue(market) * 100 / 0.9003 - 0.20;

        assertThat(future.dirtyValue(market) * 100).isCloseTo(expectedPer100, within(1e-12));
    }

    @Test
    void theCurrentProxyBondComesFromTheMarket() {
        MarketState market = market(1, 0.10);

        assertThat(future.dirtyValue(market) * 100)
                .isCloseTo(CTD2.cleanValue(market) * 100 / 0.9040 + 0.10, within(1e-12));
    }

    @Test
    void dv01BumpsTheCurveWithTheBasisHeldFixed() {
        SensitivityCalculator calculator = new SensitivityCalculator(Pillar.DEFAULTS);

        CurveSensitivities lowBasis = calculator.curveSensitivities(future, market(0, -0.5));
        CurveSensitivities highBasis = calculator.curveSensitivities(future, market(0, 2.0));
        CurveSensitivities proxyOnly = calculator.curveSensitivities(CTD1, market(0, 0));

        assertThat(lowBasis.dv01()).isPositive().isCloseTo(highBasis.dv01(), within(1e-12));
        assertThat(lowBasis.dv01()).as("the Proxy Bond's DV01 scaled by 1/CF")
                .isCloseTo(proxyOnly.dv01() / 0.9003, within(1e-12));
    }

    @Test
    void declaresItsBasisProxyBondValuationDateAndThePillarsOfEveryDeliverable() {
        var factors = future.riskFactors(market(0, 0), Pillar.DEFAULTS);

        assertThat(factors).contains(
                RiskFactorId.basis("USD", "ZNZ6"),
                RiskFactorId.proxyBond("USD", "ZNZ6"),
                RiskFactorId.valuationDate("USD"),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("7Y")),
                RiskFactorId.pillarZeroRate("USD", Pillar.parse("10Y")));
        assertThat(factors).allSatisfy(f -> assertThat(f.currency()).isEqualTo("USD"));
    }

    @Test
    void isMarginedDailyAndPaysNoCashFlows() {
        assertThat(future.marginedDaily()).isTrue();
        assertThat(future.cashFlowsPaid(market(0, 0), VALUATION, VALUATION.plusYears(10))).isEmpty();
        assertThat(future.type()).isEqualTo(InstrumentType.TREASURY_FUTURE);
    }
}
