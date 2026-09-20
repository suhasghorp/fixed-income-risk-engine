package com.fixedincomerisk.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.book.Position;
import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.FixingHistory;
import com.fixedincomerisk.market.Fixings;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.risk.SensitivityCalculator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterestRateSwapTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 9, 11);
    private static final double FLAT_RATE = 0.04;
    private static final LocalDate RESET = LocalDate.of(2026, 7, 15);
    private static final double FIXING = 0.043;

    /** Pay 3.95% fixed from 2026-07-15 to 2031-07-15: part-way through its first floating period on VALUATION. */
    private static final InterestRateSwap SEASONED = new InterestRateSwap("IRS-5Y-PAY",
            InterestRateSwap.Direction.PAY_FIXED, 0.0395, RESET, LocalDate.of(2031, 7, 15));

    private static MarketState market(LocalDate valuationDate, Map<LocalDate, Double> fixings) {
        return new MarketState(valuationDate, Map.of("USD", t -> Math.exp(-FLAT_RATE * t)), Map.of(),
                MarketState.CreditMarket.NONE, new Fixings(fixings));
    }

    private static double df(LocalDate valuationDate, LocalDate date) {
        return Math.exp(-FLAT_RATE * ChronoUnit.DAYS.between(valuationDate, date) / 365.0);
    }

    @Test
    void aParSwapAtInceptionIsWorthNothing() {
        LocalDate maturity = VALUATION.plusYears(5);
        InterestRateSwap unit = new InterestRateSwap("PAR", InterestRateSwap.Direction.PAY_FIXED, 1, VALUATION, maturity);
        MarketState opening = market(VALUATION, Map.of());
        MarketState withFixing = opening.withFixings(new Fixings(Map.of(VALUATION, FixingHistory.indexRate(opening, "USD", VALUATION))));
        // Annuity: the fixed leg of a 100% coupon, from a zero-rate swap (value = floating − annuity).
        double floatingLeg = 1 - df(VALUATION, maturity);
        double annuity = floatingLeg - unit.dirtyValue(withFixing);
        double parRate = floatingLeg / annuity;

        InterestRateSwap par = new InterestRateSwap("PAR", InterestRateSwap.Direction.PAY_FIXED, parRate, VALUATION, maturity);

        assertThat(par.dirtyValue(withFixing)).isCloseTo(0, within(1e-14));
        assertThat(parRate).isBetween(0.0395, 0.0415);
    }

    /**
     * The Annuity, hand-checked: a one-year swap has two fixed periods, both exactly half a year on
     * 30/360, so its Annuity is half of each period-end discount factor. This is the number a Swaption's
     * value scales with, and it comes off the swap's own schedule rather than a second one.
     */
    @Test
    void theAnnuityIsTheDiscountedFixedAccrualsOnTheSwapsOwnSchedule() {
        LocalDate maturity = VALUATION.plusYears(1);
        InterestRateSwap oneYear = new InterestRateSwap("IRS-1Y", InterestRateSwap.Direction.PAY_FIXED,
                0.04, VALUATION, maturity);
        MarketState market = market(VALUATION, Map.of(VALUATION, FIXING));

        assertThat(oneYear.annuity(market)).isCloseTo(
                0.5 * df(VALUATION, LocalDate.of(2027, 3, 11)) + 0.5 * df(VALUATION, maturity), within(1e-15));
        // It is the same Annuity the swap prices with: value is linear in the strike with slope −A.
        double atFourPercent = oneYear.dirtyValue(market);
        double atFivePercent = new InterestRateSwap("IRS-1Y", InterestRateSwap.Direction.PAY_FIXED,
                0.05, VALUATION, maturity).dirtyValue(market);
        assertThat((atFivePercent - atFourPercent) / 0.01).isCloseTo(-oneYear.annuity(market), within(1e-15));
    }

    /**
     * The identity that defines the Forward Swap Rate: a swap struck at it is worth nothing. It has to
     * hold for a seasoned swap valued off a Fixing and for a forward-starting one with no Fixing at all,
     * because the second is the swap a Swaption exercises into.
     */
    @Test
    void aSwapStruckAtItsForwardRateIsWorthNothing() {
        MarketState seasonedMarket = market(VALUATION, Map.of(RESET, FIXING));
        // The underlying of a 1M x 5Y swaption: it starts at the Expiry, so nothing has fixed yet.
        LocalDate expiry = VALUATION.plusMonths(1);
        InterestRateSwap forwardStarting = new InterestRateSwap("IRS-1Mx5Y",
                InterestRateSwap.Direction.PAY_FIXED, 0.04, expiry, expiry.plusYears(5));
        MarketState noFixings = market(VALUATION, Map.of());

        for (InterestRateSwap swap : new InterestRateSwap[] {SEASONED, forwardStarting}) {
            MarketState market = swap == SEASONED ? seasonedMarket : noFixings;
            double forward = swap.forwardRate(market);

            for (InterestRateSwap.Direction direction : InterestRateSwap.Direction.values()) {
                InterestRateSwap struckAtTheForward = new InterestRateSwap(
                        swap.id(), direction, forward, swap.effectiveDate(), swap.maturityDate());

                assertThat(struckAtTheForward.dirtyValue(market))
                        .as("%s %s struck at its forward %s", swap.id(), direction, forward)
                        .isCloseTo(0, within(1e-15));
            }
            // Both sides of the same swap are worth zero at the same rate, so the rate cannot depend on
            // which side you are: a Swaption's strike comparison would be direction-dependent if it did.
            assertThat(new InterestRateSwap(swap.id(), InterestRateSwap.Direction.RECEIVE_FIXED,
                    swap.fixedRate(), swap.effectiveDate(), swap.maturityDate()).forwardRate(market))
                    .isEqualTo(forward);
            // On a flat 4% curve the forward swap rate is the flat rate, up to the day-count difference
            // between the 30/360 fixed leg and the ACT/365 curve.
            assertThat(forward).isBetween(0.0395, 0.0415);
        }
    }

    /** A swap with nothing left to discount has no Forward Swap Rate, and says so rather than dividing. */
    @Test
    void aMaturedSwapHasNoForwardRate() {
        MarketState afterMaturity = market(SEASONED.maturityDate().plusDays(1), Map.of());

        assertThat(SEASONED.annuity(afterMaturity)).isZero();
        assertThat(SEASONED.dirtyValue(afterMaturity)).isZero();
        assertThatThrownBy(() -> SEASONED.forwardRate(afterMaturity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IRS-5Y-PAY")
                .hasMessageContaining("no fixed payment is left");
    }

    @Test
    void midPeriodTheCurrentCouponComesFromItsFixingAndTheRestFromTheParFormula() {
        MarketState market = market(VALUATION, Map.of(RESET, FIXING));

        // By hand: current floating period 07-15 → 10-15 (92 days, ACT/360) at the Fixing, then par to maturity;
        // ten fixed payments on the 15th of January and July, each exactly half a year on 30/360.
        LocalDate periodEnd = LocalDate.of(2026, 10, 15);
        LocalDate maturity = LocalDate.of(2031, 7, 15);
        double floating = FIXING * 92 / 360 * df(VALUATION, periodEnd) + df(VALUATION, periodEnd) - df(VALUATION, maturity);
        double fixed = 0;
        for (int year = 2027; year <= 2031; year++) {
            fixed += 0.0395 * 0.5 * df(VALUATION, LocalDate.of(year, 1, 15));
            fixed += 0.0395 * 0.5 * df(VALUATION, LocalDate.of(year, 7, 15));
        }

        assertThat(SEASONED.dirtyValue(market)).isCloseTo(floating - fixed, within(1e-15));
    }

    @Test
    void onAResetDateTheFloatingLegIsWorthParLessTheFinalDiscountFactor() {
        LocalDate reset = LocalDate.of(2026, 10, 15);
        MarketState atReset = market(reset, Map.of());
        MarketState market = atReset.withFixings(new Fixings(Map.of(RESET, FIXING, reset, FixingHistory.indexRate(atReset, "USD", reset))));
        LocalDate maturity = LocalDate.of(2031, 7, 15);
        double fixed = 0;
        for (int year = 2027; year <= 2031; year++) {
            fixed += 0.0395 * 0.5 * df(reset, LocalDate.of(year, 1, 15));
            fixed += 0.0395 * 0.5 * df(reset, LocalDate.of(year, 7, 15));
        }

        assertThat(SEASONED.dirtyValue(market)).isCloseTo((1 - df(reset, maturity)) - fixed, within(1e-14));
    }

    @Test
    void aForwardStartingSwapNeedsNoFixing() {
        LocalDate before = LocalDate.of(2026, 6, 1);
        double floating = df(before, RESET) - df(before, LocalDate.of(2031, 7, 15));

        double value = SEASONED.dirtyValue(market(before, Map.of()));

        assertThat(value).isLessThan(floating).isGreaterThan(floating - 0.0395 * 5.2);
    }

    @Test
    void theReceiverIsTheMirrorOfThePayer() {
        InterestRateSwap receiver = new InterestRateSwap("IRS-5Y-REC", InterestRateSwap.Direction.RECEIVE_FIXED, 0.0395,
                RESET, LocalDate.of(2031, 7, 15));
        MarketState market = market(VALUATION, Map.of(RESET, FIXING));

        assertThat(receiver.dirtyValue(market)).isEqualTo(-SEASONED.dirtyValue(market));
        SensitivityCalculator calculator = new SensitivityCalculator(Pillar.DEFAULTS);
        assertThat(calculator.curveSensitivities(SEASONED, market, "USD").dv01()).as("a fixed payer gains as rates rise").isNegative();
        assertThat(calculator.curveSensitivities(receiver, market, "USD").dv01()).isPositive();
    }

    @Test
    void paymentsAreProcessedAtTheirDatesWithTheRecordedFixing() {
        MarketState market = market(VALUATION, Map.of(RESET, FIXING, LocalDate.of(2026, 10, 15), 0.041));

        assertThat(SEASONED.cashFlowsPaid(market, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15)))
                .containsExactly(new CashFlow(LocalDate.of(2026, 10, 15), CashFlow.Kind.FLOATING_LEG, FIXING * (92 / 360.0), "USD"));
        assertThat(SEASONED.cashFlowsPaid(market, LocalDate.of(2027, 1, 14), LocalDate.of(2027, 1, 15))).containsExactly(
                new CashFlow(LocalDate.of(2027, 1, 15), CashFlow.Kind.FIXED_LEG, -0.0395 * 0.5, "USD"),
                new CashFlow(LocalDate.of(2027, 1, 15), CashFlow.Kind.FLOATING_LEG, 0.041 * (92 / 360.0), "USD"));
    }

    @Test
    void dependsOnlyOnExistingFactorKinds() {
        var factors = SEASONED.riskFactors(market(VALUATION, Map.of(RESET, FIXING)), Pillar.DEFAULTS);

        assertThat(factors).extracting(RiskFactorId::type)
                .containsOnly(FactorType.PILLAR_ZERO_RATE, FactorType.VALUATION_DATE);
        assertThat(factors).contains(RiskFactorId.pillarZeroRate("USD", Pillar.parse("5Y")));
        assertThat(SEASONED.description()).isEqualTo("Pay 3.950% fixed vs USD-3M to 07/15/2031");
    }

    @Test
    void aSwapPositionMustHaveAPositiveNotional() {
        assertThat(new Position("PAYER", SEASONED, 20_000_000).quantity()).isEqualTo(20_000_000);
        assertThatThrownBy(() -> new Position("SHORT_PAYER", SEASONED, -20_000_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHORT_PAYER")
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new Position("ZERO", SEASONED, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReferenceData.parse(new ReferenceData.Csv("", "", "", "", "",
                "swapId,direction,fixedRate,effectiveDate,maturityDate\nIRS-5Y-PAY,PAY_FIXED,3.950,2026-07-15,2031-07-15\n",
                "positionId,instrumentId,quantity\nP16,IRS-5Y-PAY,-20000000\n")))
                .as("reference data with a negative swap Position fails at startup")
                .hasMessageContaining("P16");
    }

    @Test
    void theSchedulesRollForwardFromTheEffectiveDate() {
        assertThat(SEASONED.floatingPeriods()).hasSize(20).first()
                .isEqualTo(new InterestRateSwap.Period(RESET, LocalDate.of(2026, 10, 15)));
        assertThat(SEASONED.fixedPeriods()).hasSize(10);
        assertThat(SEASONED.currentFloatingPeriod(VALUATION))
                .contains(new InterestRateSwap.Period(RESET, LocalDate.of(2026, 10, 15)));
        assertThat(SEASONED.currentFloatingPeriod(LocalDate.of(2026, 10, 15)))
                .contains(new InterestRateSwap.Period(LocalDate.of(2026, 10, 15), LocalDate.of(2027, 1, 15)));
    }
}
