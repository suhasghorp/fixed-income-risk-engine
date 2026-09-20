package com.fixedincomerisk.instrument;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A fixed-coupon bond's semi-annual schedule: coupon dates rolled back from maturity in six-month steps,
 * with the end-of-month rule, down to (but excluding) the dated date. Dates are unadjusted.
 */
public record SemiAnnualSchedule(LocalDate datedDate, LocalDate maturityDate) {

    public SemiAnnualSchedule {
        if (!maturityDate.isAfter(datedDate)) {
            throw new IllegalArgumentException("Maturity must be after dated date");
        }
    }

    /** Coupon payment dates after the dated date, ascending, ending with maturity. */
    public List<LocalDate> couponDates() {
        List<LocalDate> dates = new ArrayList<>();
        for (int months = 0; ; months += 6) {
            LocalDate date = rollBack(months);
            if (!date.isAfter(datedDate)) {
                break;
            }
            dates.add(date);
        }
        Collections.reverse(dates);
        return dates;
    }

    /** Every coupon at {@code couponRate / 2} per unit, then the redemption of principal at maturity. */
    public List<CashFlow> cashFlows(double couponRate, String currency) {
        List<CashFlow> cashFlows = new ArrayList<>();
        for (LocalDate couponDate : couponDates()) {
            cashFlows.add(new CashFlow(couponDate, CashFlow.Kind.COUPON, couponRate / 2, currency));
        }
        cashFlows.add(new CashFlow(maturityDate, CashFlow.Kind.REDEMPTION, 1, currency));
        return cashFlows;
    }

    /**
     * The coupon period accruing on {@code valuationDate}, or empty before the dated date and from maturity
     * on. A coupon date starts the next period, so accrual is zero on it.
     */
    public Optional<AccrualPeriod> accrualPeriod(LocalDate valuationDate) {
        if (!valuationDate.isAfter(datedDate) || !valuationDate.isBefore(maturityDate)) {
            return Optional.empty();
        }
        LocalDate previous = datedDate;
        LocalDate next = maturityDate;
        for (LocalDate couponDate : couponDates()) {
            if (!couponDate.isAfter(valuationDate)) {
                previous = couponDate;
            } else {
                next = couponDate;
                break;
            }
        }
        return Optional.of(new AccrualPeriod(previous, next, regularPeriodStart(next)));
    }

    private LocalDate regularPeriodStart(LocalDate couponDate) {
        long monthsFromMaturity = ChronoUnit.MONTHS.between(couponDate.withDayOfMonth(1), maturityDate.withDayOfMonth(1));
        return rollBack((int) monthsFromMaturity + 6);
    }

    private LocalDate rollBack(int months) {
        LocalDate date = maturityDate.minusMonths(months);
        boolean endOfMonth = maturityDate.equals(maturityDate.with(TemporalAdjusters.lastDayOfMonth()));
        return endOfMonth ? date.with(TemporalAdjusters.lastDayOfMonth()) : date;
    }

    /**
     * @param accrualStart the previous coupon date, or the dated date in a first period
     * @param nextCoupon   the coupon date that ends the period
     * @param regularStart where a full six-month period ending on {@code nextCoupon} would start; differs
     *                     from {@code accrualStart} only in an irregular first period
     */
    public record AccrualPeriod(LocalDate accrualStart, LocalDate nextCoupon, LocalDate regularStart) {
    }
}
