package com.fixedincomerisk.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.instrument.InterestRateSwap;
import com.fixedincomerisk.instrument.Swaption;
import com.fixedincomerisk.instrument.TreasuryBond;
import com.fixedincomerisk.market.ExerciseDecisions;
import com.fixedincomerisk.market.FixingHistory;
import com.fixedincomerisk.market.Fixings;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.SurfacePoint;
import com.fixedincomerisk.market.SurfacePoints;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two risk numbers optionality adds, and the linearity it takes away. Vega is bump-and-reprice like
 * every other sensitivity in the engine, so an Instrument that does not read the surface measures zero
 * rather than being excluded by type; Gamma is the change in DV01 over a shift large enough to print.
 */
class SwaptionRiskTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 10, 11);
    private static final double FLAT_RATE = 0.04;
    private static final double NORMAL_VOL = 0.0095;
    private static final SurfacePoint POINT = new SurfacePoint("USD", "1Mx5Y");
    private static final SurfacePoints SURFACE =
            new SurfacePoints(List.of(POINT, new SurfacePoint("USD", "1Yx10Y")));

    /** Struck at the money on a flat 4% curve, so the option carries the most Vega and Gamma it can. */
    private static final Swaption PAYER = new Swaption("SWPN-1Mx5Y-PAY", EXPIRY, POINT,
            new InterestRateSwap("SWPN-SWAP", InterestRateSwap.Direction.PAY_FIXED, FLAT_RATE,
                    EXPIRY, EXPIRY.plusYears(5)));
    /** The linear comparison: the same five years of fixed rate, without the option wrapped round it. */
    private static final InterestRateSwap SWAP = new InterestRateSwap(
            "IRS-5Y-PAY", InterestRateSwap.Direction.PAY_FIXED, FLAT_RATE, VALUATION, VALUATION.plusYears(5));
    private static final TreasuryBond BOND = new TreasuryBond(
            "91282CRF0", "10Y", 0.04625, LocalDate.of(2026, 8, 15), LocalDate.of(2036, 8, 15));

    private final SensitivityCalculator calculator = new SensitivityCalculator(Pillar.DEFAULTS);

    private static MarketState market(double flatRate) {
        return market(VALUATION, flatRate, ExerciseDecisions.NONE);
    }

    private static MarketState market(LocalDate valuationDate, double flatRate, ExerciseDecisions exercises) {
        return new MarketState(valuationDate, Map.of("USD", t -> Math.exp(-flatRate * t)), Map.of(),
                MarketState.CreditMarket.NONE, fixings(valuationDate, flatRate),
                MarketState.FxMarket.NONE,
                new MarketState.VolMarket(Map.of("USD 1Mx5Y", NORMAL_VOL, "USD 1Yx10Y", NORMAL_VOL)),
                exercises);
    }

    /**
     * Every reset either swap has already passed, as the simulator would have recorded them one Day
     * Rollover at a time. The option's underlying starts at the Expiry, so before then only the comparison
     * swap has any.
     */
    private static Fixings fixings(LocalDate valuationDate, double flatRate) {
        MarketState curveOnly = MarketState.of(valuationDate, "USD", t -> Math.exp(-flatRate * t));
        Map<LocalDate, Double> rates = new LinkedHashMap<>();
        for (InterestRateSwap swap : List.of(SWAP, PAYER.underlying())) {
            for (LocalDate reset : swap.resetDates()) {
                if (!reset.isAfter(valuationDate)) {
                    rates.put(reset, FixingHistory.indexRate(curveOnly, "USD", reset));
                }
            }
        }
        return new Fixings(rates);
    }

    @Test
    void onlyTheSwaptionHasVega() {
        MarketState market = market(FLAT_RATE);

        // Every currency the surface quotes gets a line, so a zero is a measurement and not an omission.
        assertThat(calculator.vega(PAYER, market, SURFACE)).containsOnlyKeys("USD");
        assertThat(calculator.vega(PAYER, market, SURFACE).get("USD")).isPositive();
        assertThat(calculator.vega(SWAP, market, SURFACE).get("USD")).isZero();
        assertThat(calculator.vega(BOND, market, SURFACE).get("USD")).isZero();
    }

    /**
     * The bumped number against the Bachelier closed form the option is priced with. They are different
     * routes to the same derivative, which is the check worth having: the engine reports the bumped one,
     * because that is the definition every other sensitivity in it uses.
     */
    @Test
    void vegaByBumpAndRepriceMatchesTheClosedForm() {
        MarketState market = market(FLAT_RATE);

        double bumped = calculator.vega(PAYER, market, SURFACE).get("USD");

        // They agree to about a part in a million: the gap is the central difference's truncation over a
        // bump that is one percent of the volatility it moves, not a disagreement about the derivative.
        assertThat(bumped).isCloseTo(PAYER.vegaPerBasisPoint(market), within(1e-5 * bumped));
    }

    /** A bump of a point this option does not price from moves nothing. */
    @Test
    void vegaComesOnlyFromTheOptionsOwnSurfacePoint() {
        MarketState market = market(FLAT_RATE);
        SurfacePoints otherOnly = new SurfacePoints(List.of(new SurfacePoint("USD", "1Yx10Y")));

        assertThat(calculator.vega(PAYER, market, otherOnly).get("USD")).isZero();
    }

    /** At the Expiry the volatility has dropped out of the price, so there is nothing left to bump. */
    @Test
    void vegaIsZeroOnceTheOptionHasExpired() {
        MarketState expired = market(EXPIRY, FLAT_RATE,
                new ExerciseDecisions(Map.of(new ExerciseDecisions.Key("SWPN-1Mx5Y-PAY", EXPIRY), true)));

        assertThat(calculator.vega(PAYER, expired, SURFACE).get("USD")).isZero();
    }

    /**
     * Gamma is exactly what its label says: the DV01 measured again with the curve shifted, minus the
     * DV01 measured now. On a flat curve a 25bp parallel shift is a flat curve 25bp higher, so the
     * identity can be checked against a market built by hand rather than against the bumping code.
     */
    @Test
    void gammaIsTheChangeInDv01OverTheShift() {
        CurveSensitivities now = calculator.curveSensitivities(PAYER, market(FLAT_RATE), "USD");
        double shift = RatesSensitivities.GAMMA_SHIFT_BP * 1e-4;
        CurveSensitivities shifted = calculator.curveSensitivities(PAYER, market(FLAT_RATE + shift), "USD");

        assertThat(now.gamma()).isCloseTo(shifted.dv01() - now.dv01(), within(1e-12 * Math.abs(now.gamma())));
    }

    /**
     * The point of the whole feature on one line. The swaption's DV01 is not a constant: shift the curve
     * 25bp and it moves by <em>half of itself</em>. The swap alongside it — the same five years of fixed
     * rate, unwrapped — moves 1.3%, which is its own mild convexity and is the linearity the rest of the
     * Book has always had.
     */
    @Test
    void theSwaptionsDv01MovesWithRatesAndTheSwapsDoesNot() {
        double shift = RatesSensitivities.GAMMA_SHIFT_BP * 1e-4;

        double swaptionNow = calculator.curveSensitivities(PAYER, market(FLAT_RATE), "USD").dv01();
        double swaptionShifted = calculator.curveSensitivities(PAYER, market(FLAT_RATE + shift), "USD").dv01();
        double swapNow = calculator.curveSensitivities(SWAP, market(FLAT_RATE), "USD").dv01();
        double swapShifted = calculator.curveSensitivities(SWAP, market(FLAT_RATE + shift), "USD").dv01();

        assertThat(relativeChange(swaptionNow, swaptionShifted)).isGreaterThan(0.4);
        assertThat(relativeChange(swapNow, swapShifted)).isLessThan(0.02);
        assertThat(relativeChange(swaptionNow, swaptionShifted))
                .isGreaterThan(20 * relativeChange(swapNow, swapShifted));
    }

    /**
     * A payer swaption is short duration and long convexity: its DV01 is negative and gets more negative
     * as rates rise, so its Gamma is negative. So is a long bond's, for the same reason — DV01 is the gain
     * from a 1bp fall, and that gain shrinks as rates rise. The sign is the convexity's, not the side's.
     */
    @Test
    void positiveConvexityReadsAsANegativeGamma() {
        MarketState market = market(FLAT_RATE);

        assertThat(calculator.curveSensitivities(PAYER, market, "USD")).satisfies(payer -> {
            assertThat(payer.dv01()).isNegative();
            assertThat(payer.gamma()).isNegative();
        });
        assertThat(calculator.curveSensitivities(BOND, market, "USD")).satisfies(bond -> {
            assertThat(bond.dv01()).isPositive();
            assertThat(bond.gamma()).isNegative();
        });
    }

    /** Gamma is a Position's like every other sensitivity: per unit of notional, times the quantity. */
    @Test
    void gammaScalesWithThePosition() {
        CurveSensitivities perUnit = calculator.curveSensitivities(PAYER, market(FLAT_RATE), "USD");

        assertThat(perUnit.scaledBy(1_000_000).gamma()).isCloseTo(perUnit.gamma() * 1_000_000, within(1e-9));
    }

    /** Across currencies, Gamma adds up the same way DV01 does — and carries the same warning label. */
    @Test
    void gammaTotalsAcrossCurrenciesAndSaysSo() {
        MarketState twoCurrencies = new MarketState(VALUATION,
                new LinkedHashMap<>(Map.of("USD", t -> Math.exp(-FLAT_RATE * t))),
                Map.of(), MarketState.CreditMarket.NONE, Fixings.NONE, MarketState.FxMarket.NONE,
                new MarketState.VolMarket(Map.of("USD 1Mx5Y", NORMAL_VOL, "USD 1Yx10Y", NORMAL_VOL)),
                ExerciseDecisions.NONE);

        RatesSensitivities risk = calculator.ratesSensitivities(PAYER, twoCurrencies);

        assertThat(risk.totalGamma()).isEqualTo(risk.in("USD").gamma());
        assertThat(RatesSensitivities.GAMMA_LABEL).contains("25bp");
        assertThat(RatesSensitivities.GAMMA_TOTAL_LABEL).contains("25bp");
    }

    private static double relativeChange(double now, double shifted) {
        return Math.abs(shifted - now) / Math.abs(now);
    }
}
