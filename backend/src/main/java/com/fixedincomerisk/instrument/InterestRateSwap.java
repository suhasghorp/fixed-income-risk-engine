package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.FixingHistory;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A vanilla fixed-for-floating USD interest rate swap, valued per unit of notional off the single
 * simulated curve for both discounting and projection. A real desk discounts on the OIS curve and projects
 * the floating rate off the index's own curve; one curve keeps the swap a pure function of the simulated
 * Treasury curve, at the cost of ignoring the basis between them. The fixed leg pays semi-annually
 * on 30/360; the floating leg resets quarterly to the 3-month index, ACT/360, set at the start of each
 * period. Dates are unadjusted, rolled forward from the effective date with the end-of-month rule.
 *
 * <p>The current floating period's coupon is known from its recorded Fixing; the periods after it are
 * valued by the par formula: V_float = L_fix·τ_k·P(T_k) + P(T_k) − P(T_end).
 *
 * @param fixedRate annual fixed rate as a decimal
 */
public record InterestRateSwap(String id, Direction direction, double fixedRate, LocalDate effectiveDate,
                               LocalDate maturityDate) implements Instrument {

    private static final int FIXED_MONTHS = 6;
    private static final int FLOATING_MONTHS = 3;
    private static final DateTimeFormatter MATURITY = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    public enum Direction {
        /** Pays fixed, receives floating: gains when rates rise. */
        PAY_FIXED(1),
        /** Receives fixed, pays floating: gains when rates fall. */
        RECEIVE_FIXED(-1);

        private final int sign;

        Direction(int sign) {
            this.sign = sign;
        }
    }

    public InterestRateSwap {
        if (!maturityDate.isAfter(effectiveDate)) {
            throw new IllegalArgumentException("Maturity must be after the effective date for " + id);
        }
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.INTEREST_RATE_SWAP;
    }

    /** The quantity is the notional; paying or receiving fixed is part of the terms. */
    @Override
    public boolean requiresPositiveQuantity() {
        return true;
    }

    @Override
    public String currency() {
        return "USD";
    }

    @Override
    public String description() {
        return String.format(Locale.US, "%s %.3f%% fixed vs %s to %s",
                direction == Direction.PAY_FIXED ? "Pay" : "Receive", fixedRate * 100, FixingHistory.INDEX,
                maturityDate.format(MATURITY));
    }

    /** The Valuation Date and the Pillars around every remaining payment (and the start, if forward-starting). */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        LocalDate valuationDate = market.valuationDate();
        Set<RiskFactorId> factors = new LinkedHashSet<>();
        factors.add(RiskFactorId.valuationDate(currency()));
        List<LocalDate> dates = new ArrayList<>();
        if (effectiveDate.isAfter(valuationDate)) {
            dates.add(effectiveDate);
        }
        fixedPeriods().forEach(p -> dates.add(p.end()));
        floatingPeriods().forEach(p -> dates.add(p.end()));
        for (LocalDate date : dates) {
            if (date.isAfter(valuationDate)) {
                for (Pillar pillar : Pillar.around(pillars, YearFractions.act365(valuationDate, date))) {
                    factors.add(RiskFactorId.pillarZeroRate(currency(), pillar));
                }
            }
        }
        return factors;
    }

    /** Value per unit of notional to this swap's holder: floating leg minus fixed leg for the fixed payer. */
    @Override
    public double dirtyValue(MarketState market) {
        LocalDate valuationDate = market.valuationDate();
        if (!maturityDate.isAfter(valuationDate)) {
            return 0;
        }
        double fixed = 0;
        for (Period period : fixedPeriods()) {
            if (period.end().isAfter(valuationDate)) {
                fixed += fixedRate * fixedAccrual(period) * discountFactor(market, period.end());
            }
        }
        double floating;
        Optional<Period> current = currentFloatingPeriod(valuationDate);
        if (current.isPresent()) {
            Period period = current.get();
            double fixing = market.fixings().rate(period.start());
            floating = fixing * floatingAccrual(period) * discountFactor(market, period.end())
                    + discountFactor(market, period.end()) - discountFactor(market, maturityDate);
        } else {
            floating = discountFactor(market, effectiveDate) - discountFactor(market, maturityDate);
        }
        return direction.sign * (floating - fixed);
    }

    /** Fixed and floating payments dated after {@code from} and on or before {@code to}, signed for the holder. */
    @Override
    public List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to) {
        List<CashFlow> paid = new ArrayList<>();
        for (Period period : fixedPeriods()) {
            if (period.end().isAfter(from) && !period.end().isAfter(to)) {
                paid.add(new CashFlow(period.end(), CashFlow.Kind.FIXED_LEG,
                        -direction.sign * fixedRate * fixedAccrual(period), currency()));
            }
        }
        for (Period period : floatingPeriods()) {
            if (period.end().isAfter(from) && !period.end().isAfter(to)) {
                paid.add(new CashFlow(period.end(), CashFlow.Kind.FLOATING_LEG,
                        direction.sign * market.fixings().rate(period.start()) * floatingAccrual(period),
                        currency()));
            }
        }
        return paid;
    }

    /** The floating period accruing on {@code valuationDate}: its start is on or before it, its end after. */
    public Optional<Period> currentFloatingPeriod(LocalDate valuationDate) {
        return floatingPeriods().stream()
                .filter(p -> !p.start().isAfter(valuationDate) && p.end().isAfter(valuationDate))
                .findFirst();
    }

    /** The floating reset dates: the start of every floating period. */
    public List<LocalDate> resetDates() {
        return floatingPeriods().stream().map(Period::start).toList();
    }

    public List<Period> fixedPeriods() {
        return periods(FIXED_MONTHS);
    }

    public List<Period> floatingPeriods() {
        return periods(FLOATING_MONTHS);
    }

    static double fixedAccrual(Period period) {
        return YearFractions.days30360(period.start(), period.end()) / 360.0;
    }

    static double floatingAccrual(Period period) {
        return ChronoUnit.DAYS.between(period.start(), period.end()) / 360.0;
    }

    private List<Period> periods(int months) {
        boolean endOfMonth = effectiveDate.equals(effectiveDate.with(TemporalAdjusters.lastDayOfMonth()));
        List<Period> periods = new ArrayList<>();
        LocalDate start = effectiveDate;
        for (int i = 1; start.isBefore(maturityDate); i++) {
            LocalDate end = effectiveDate.plusMonths((long) i * months);
            if (endOfMonth) {
                end = end.with(TemporalAdjusters.lastDayOfMonth());
            }
            if (end.isAfter(maturityDate)) {
                end = maturityDate;
            }
            periods.add(new Period(start, end));
            start = end;
        }
        return periods;
    }

    private double discountFactor(MarketState market, LocalDate date) {
        return market.curve(currency()).discountFactor(YearFractions.act365(market.valuationDate(), date));
    }

    public record Period(LocalDate start, LocalDate end) {
    }
}
