package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.market.ExerciseDecisions;
import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.FixingHistory;
import com.fixedincomerisk.market.Fixings;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.market.SurfacePoint;
import com.fixedincomerisk.model.BachelierModel;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A Swaption owns no terms of its own: strike, direction and Expiry all come from the swap it exercises
 * into, so a swaption that disagrees with that swap cannot be built.
 */
class SwaptionTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 10, 11);
    private static final double FLAT_RATE = 0.04;
    private static final SurfacePoint POINT = new SurfacePoint("USD", "1Mx5Y");
    private static final SurfacePoint OTHER_POINT = new SurfacePoint("USD", "1Yx10Y");

    private static InterestRateSwap underlying(InterestRateSwap.Direction direction, double strike) {
        return new InterestRateSwap("SWPN-SWAP", direction, strike, EXPIRY, EXPIRY.plusYears(5));
    }

    private static Swaption payer(double strike) {
        return new Swaption("SWPN-1Mx5Y-PAY", EXPIRY, POINT,
                underlying(InterestRateSwap.Direction.PAY_FIXED, strike));
    }

    private static Swaption receiver(double strike) {
        return new Swaption("SWPN-1Mx5Y-REC", EXPIRY, POINT,
                underlying(InterestRateSwap.Direction.RECEIVE_FIXED, strike));
    }

    private static MarketState market(LocalDate valuationDate, double normalVol) {
        return market(valuationDate, normalVol, FLAT_RATE, ExerciseDecisions.NONE);
    }

    private static MarketState market(LocalDate valuationDate, double normalVol, double flatRate,
                                      ExerciseDecisions exercises) {
        return new MarketState(valuationDate, Map.of("USD", t -> Math.exp(-flatRate * t)), Map.of(),
                MarketState.CreditMarket.NONE, fixings(valuationDate, flatRate), MarketState.FxMarket.NONE,
                new MarketState.VolMarket(Map.of("USD 1Mx5Y", normalVol, "USD 1Yx10Y", normalVol)), exercises);
    }

    /**
     * Every reset the underlying has already passed, as the simulator would have recorded them one Day
     * Rollover at a time. Its first floating period starts at the Expiry, so before then there are none.
     */
    private static Fixings fixings(LocalDate valuationDate, double flatRate) {
        MarketState curveOnly = MarketState.of(valuationDate, "USD", t -> Math.exp(-flatRate * t));
        Map<LocalDate, Double> rates = new LinkedHashMap<>();
        for (LocalDate reset : underlying(InterestRateSwap.Direction.PAY_FIXED, 0.04).resetDates()) {
            if (!reset.isAfter(valuationDate)) {
                rates.put(reset, FixingHistory.indexRate(curveOnly, "USD", reset));
            }
        }
        return new Fixings(rates);
    }

    private static ExerciseDecisions decided(String swaptionId, boolean exercised) {
        return new ExerciseDecisions(Map.of(new ExerciseDecisions.Key(swaptionId, EXPIRY), exercised));
    }

    @Test
    void aSwaptionThatDisagreesWithTheSwapItExercisesIntoCannotBeBuilt() {
        assertThatThrownBy(() -> new Swaption("SWPN-BAD", EXPIRY.plusDays(1), POINT,
                underlying(InterestRateSwap.Direction.PAY_FIXED, 0.04)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SWPN-BAD")
                .hasMessageContaining("the Expiry is the underlying's effective date");
        // A euro Surface Point on a dollar swap is the same class of mistake.
        assertThatThrownBy(() -> new Swaption("SWPN-BAD", EXPIRY, new SurfacePoint("EUR", "1Mx5Y"),
                underlying(InterestRateSwap.Direction.PAY_FIXED, 0.04)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SWPN-BAD")
                .hasMessageContaining("USD swap");
    }

    /** The strike and the direction are read off the underlying, so they cannot be set to disagree. */
    @Test
    void theStrikeAndTheDirectionAreTheUnderlyingSwapsOwn() {
        assertThat(payer(0.0480).strike()).isEqualTo(0.0480);
        assertThat(payer(0.0480).isPayer()).isTrue();
        assertThat(receiver(0.0510).isPayer()).isFalse();
        assertThat(payer(0.0480).type()).isEqualTo(InstrumentType.SWAPTION);
        assertThat(payer(0.0480).currency()).isEqualTo("USD");
        assertThat(payer(0.0480).requiresPositiveQuantity()).isTrue();
        assertThat(payer(0.0480).marginedDaily()).isFalse();
        assertThat(payer(0.0480).description()).isEqualTo("Payer 1Mx5Y swaption at 4.800%, expires 10/11/2026");
        assertThat(receiver(0.0510).description())
                .isEqualTo("Receiver 1Mx5Y swaption at 5.100%, expires 10/11/2026");
        // Exercise pays nothing: it changes what the Position is. ADR-0012.
        assertThat(payer(0.0480).cashFlowsPaid(market(VALUATION, 0.0095), VALUATION, VALUATION.plusYears(10)))
                .isEmpty();
    }

    /** One Surface Point, named, and not the other one: there is no grid to interpolate across. */
    @Test
    void itDependsOnItsOwnSurfacePointAndNotTheOther() {
        var factors = payer(0.0480).riskFactors(market(VALUATION, 0.0095), Pillar.DEFAULTS);

        assertThat(factors).contains(RiskFactorId.normalVol("USD", "1Mx5Y"));
        assertThat(factors).doesNotContain(OTHER_POINT.volFactor());
        assertThat(factors).filteredOn(f -> f.type() == FactorType.NORMAL_VOL).hasSize(1);
        // The Valuation Date, and the Pillars the underlying swap itself is exposed to — no second walk
        // over the schedule, so the option depends on exactly the curve it is priced off.
        assertThat(factors).contains(RiskFactorId.valuationDate("USD"));
        assertThat(factors).containsAll(
                underlying(InterestRateSwap.Direction.PAY_FIXED, 0.0480)
                        .riskFactors(market(VALUATION, 0.0095), Pillar.DEFAULTS));
        assertThat(factors).filteredOn(f -> f.type() == FactorType.PILLAR_ZERO_RATE).isNotEmpty();
    }

    /**
     * The premium: what the Bachelier model says, off the swap's own Annuity and Forward Swap Rate. An
     * option is bought, so it is never negative however far the market has moved against it.
     */
    @Test
    void theValueIsTheBachelierPremiumAndIsNeverNegative() {
        MarketState market = market(VALUATION, 0.0095);
        InterestRateSwap swap = underlying(InterestRateSwap.Direction.PAY_FIXED, 0.0480);
        double annuity = swap.annuity(market);
        double forward = swap.forwardRate(market);

        assertThat(payer(0.0480).dirtyValue(market))
                .isEqualTo(BachelierModel.payer(annuity, forward, 0.0480, 0.0095,
                        30 / 365.0));
        // Struck far out of the money either way, and still a premium rather than a liability.
        for (double strike : new double[] {-0.02, 0.0, 0.02, 0.04, forward, 0.06, 0.10, 0.25}) {
            assertThat(payer(strike).dirtyValue(market)).as("payer struck at %s", strike).isNotNegative();
            assertThat(receiver(strike).dirtyValue(market)).as("receiver struck at %s", strike).isNotNegative();
        }
        // A payer and a receiver at the same strike differ by the forward swap they exercise into.
        assertThat(payer(0.0480).dirtyValue(market) - receiver(0.0480).dirtyValue(market))
                .isCloseTo(annuity * (forward - 0.0480), within(1e-12));
        // More volatility is worth more, on both sides.
        assertThat(payer(0.0480).dirtyValue(market(VALUATION, 0.0150)))
                .isGreaterThan(payer(0.0480).dirtyValue(market));
        assertThat(receiver(0.0480).dirtyValue(market(VALUATION, 0.0150)))
                .isGreaterThan(receiver(0.0480).dirtyValue(market));
    }

    /** An unexercised Swaption is worth exactly zero, and depends on nothing but the Valuation Date. */
    @Test
    void anUnexercisedSwaptionIsWorthExactlyZeroAndCarriesNoRisk() {
        Swaption swaption = payer(0.0480);
        ExerciseDecisions notExercised = decided(swaption.id(), false);

        assertThat(swaption.dirtyValue(market(EXPIRY.minusDays(1), 0.0095))).isPositive();
        assertThat(swaption.vegaPerBasisPoint(market(EXPIRY.minusDays(1), 0.0095))).isPositive();

        for (LocalDate date : new LocalDate[] {EXPIRY, EXPIRY.plusDays(1), EXPIRY.plusYears(1)}) {
            MarketState market = market(date, 0.0095, FLAT_RATE, notExercised);

            assertThat(swaption.dirtyValue(market)).as("value on %s", date).isZero();
            assertThat(swaption.vegaPerBasisPoint(market)).as("vega on %s", date).isZero();
            assertThat(swaption.riskFactors(market, Pillar.DEFAULTS))
                    .as("dependencies on %s", date)
                    .containsExactly(RiskFactorId.valuationDate("USD"));
        }
    }

    /**
     * An exercised Swaption is its underlying swap, to the last digit: same value, same dependencies. It
     * is a different Instrument wearing the same Position, which is the whole of ADR-0012.
     */
    @Test
    void anExercisedSwaptionIsWorthExactlyTheUnderlyingSwap() {
        Swaption swaption = payer(0.0480);
        InterestRateSwap swap = underlying(InterestRateSwap.Direction.PAY_FIXED, 0.0480);
        ExerciseDecisions exercised = decided(swaption.id(), true);

        for (LocalDate date : new LocalDate[] {EXPIRY, EXPIRY.plusDays(1), EXPIRY.plusYears(2)}) {
            MarketState market = market(date, 0.0095, FLAT_RATE, exercised);

            assertThat(swaption.dirtyValue(market)).as("value on %s", date)
                    .isEqualTo(swap.dirtyValue(market));
            assertThat(swaption.riskFactors(market, Pillar.DEFAULTS)).as("dependencies on %s", date)
                    .isEqualTo(swap.riskFactors(market, Pillar.DEFAULTS));
        }

        // The swap's coupons flow as ordinary Lifecycle Events once the Position holds a swap. The first
        // floating period runs from the Expiry to 2027-01-11 and pays on the Day Rollover onto it.
        LocalDate firstPayment = LocalDate.of(2027, 1, 11);
        MarketState paying = market(firstPayment, 0.0095, FLAT_RATE, exercised);
        assertThat(swaption.cashFlowsPaid(paying, firstPayment.minusDays(1), firstPayment))
                .isNotEmpty()
                .isEqualTo(swap.cashFlowsPaid(paying, firstPayment.minusDays(1), firstPayment));
        // An unexercised one pays nothing on the same day: there is no swap to pay it.
        assertThat(payer(0.0480).cashFlowsPaid(
                market(firstPayment, 0.0095, FLAT_RATE, decided(swaption.id(), false)),
                firstPayment.minusDays(1), firstPayment)).isEmpty();

        // A struck-at-the-money payer exercised into a falling market is a loss: past Expiry this is a
        // swap, so it can and does go negative, which an option never could.
        MarketState ratesFell = market(EXPIRY.plusDays(1), 0.0095, 0.02, exercised);
        assertThat(swaption.dirtyValue(ratesFell)).isNegative();
    }

    /** The volatility drops out at Expiry whichever way the decision went: intrinsic has no vega. */
    @Test
    void noSwaptionDependsOnVolatilityAfterItsExpiry() {
        Swaption swaption = payer(0.0480);

        for (boolean exercised : new boolean[] {true, false}) {
            MarketState market = market(EXPIRY, 0.0095, FLAT_RATE, decided(swaption.id(), exercised));

            assertThat(swaption.riskFactors(market, Pillar.DEFAULTS))
                    .as("exercised=%s", exercised)
                    .noneMatch(factor -> factor.type() == FactorType.NORMAL_VOL);
        }
    }

    /**
     * A Swaption past its Expiry with no decision recorded is a bug in the simulation, not an option that
     * expired worthless — and the two are worth very different amounts, so it says so rather than
     * quietly picking the cheaper one.
     */
    @Test
    void pastExpiryWithNoRecordedDecisionFailsRatherThanAssumingItLapsed() {
        assertThatThrownBy(() -> payer(0.0480).dirtyValue(market(EXPIRY, 0.0095)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Exercise Decision recorded for SWPN-1Mx5Y-PAY")
                .hasMessageContaining(EXPIRY.toString());
    }

    /** Time decay: the same option is worth less the closer it gets, all else equal. */
    @Test
    void thePremiumDecaysAsTheExpiryApproaches() {
        Swaption atTheMoney = payer(underlying(InterestRateSwap.Direction.PAY_FIXED, 0.04)
                .forwardRate(market(VALUATION, 0.0095)));

        double thirtyDaysOut = atTheMoney.dirtyValue(market(VALUATION, 0.0095));
        double tenDaysOut = atTheMoney.dirtyValue(market(EXPIRY.minusDays(10), 0.0095));
        double oneDayOut = atTheMoney.dirtyValue(market(EXPIRY.minusDays(1), 0.0095));

        assertThat(thirtyDaysOut).isGreaterThan(tenDaysOut).isGreaterThan(oneDayOut);
        assertThat(oneDayOut).isPositive();
    }
}
