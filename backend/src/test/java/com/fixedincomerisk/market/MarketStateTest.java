package com.fixedincomerisk.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * There is no "the curve". Every way into a market names its currency, and naming one the market does not
 * have fails loudly rather than quietly discounting on another currency's curve.
 */
class MarketStateTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);

    private static YieldCurve flat(double rate) {
        return t -> Math.exp(-rate * t);
    }

    private static MarketState twoCurrencies() {
        Map<String, YieldCurve> curves = new LinkedHashMap<>();
        curves.put("USD", flat(0.04));
        curves.put("EUR", flat(0.025));
        return new MarketState(VALUATION, curves);
    }

    @Test
    void eachCurrencyKeepsItsOwnCurve() {
        MarketState market = twoCurrencies();

        assertThat(market.currencies()).containsExactly("USD", "EUR");
        assertThat(market.curve("USD").zeroRate(5)).isCloseTo(0.04, within(1e-12));
        assertThat(market.curve("EUR").zeroRate(5)).isCloseTo(0.025, within(1e-12));
    }

    @Test
    void anUnknownCurrencyFailsWithAMessageNamingWhatIsThere() {
        assertThatThrownBy(() -> twoCurrencies().curve("KRW"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW")
                .hasMessageContaining("[USD, EUR]");
    }

    @Test
    void bumpingOneCurrencyLeavesTheOthersAlone() {
        MarketState bumped = twoCurrencies().withCurve("EUR", flat(0.035));

        assertThat(bumped.curve("EUR").zeroRate(5)).isCloseTo(0.035, within(1e-12));
        assertThat(bumped.curve("USD").zeroRate(5)).as("USD untouched").isCloseTo(0.04, within(1e-12));
        assertThat(bumped.currencies()).as("and the reporting order is unchanged").containsExactly("USD", "EUR");
    }

    @Test
    void bumpingACurrencyTheMarketDoesNotHaveIsRefusedRatherThanAddingIt() {
        assertThatThrownBy(() -> twoCurrencies().withCurve("KRW", flat(0.03)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW");
    }

    @Test
    void aRiskFactorReadsItsOwnCurrencysCurve() {
        MarketState market = twoCurrencies();

        assertThat(market.riskFactorValue(RiskFactorId.pillarZeroRate("EUR", Pillar.parse("5Y"))))
                .isCloseTo(0.025, within(1e-12));
        assertThat(market.riskFactorValue(RiskFactorId.pillarZeroRate("USD", Pillar.parse("5Y"))))
                .isCloseTo(0.04, within(1e-12));
        assertThatThrownBy(() -> market.riskFactorValue(RiskFactorId.pillarZeroRate("KRW", Pillar.parse("5Y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KRW");
    }
}
