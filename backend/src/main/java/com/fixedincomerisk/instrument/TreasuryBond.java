package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A US Treasury note or bond: fixed semi-annual coupons, ACT/ACT (ICMA) accrual, coupon dates rolled
 * back from maturity with the end-of-month rule.
 *
 * @param cusip        identifier, used as the Instrument id
 * @param term         original term label, e.g. "10Y"
 * @param couponRate   annual coupon as a decimal
 * @param datedDate    date interest starts accruing
 * @param maturityDate final principal and coupon date
 */
public record TreasuryBond(String cusip, String term, double couponRate, LocalDate datedDate, LocalDate maturityDate)
        implements Instrument {

    private static final DateTimeFormatter MATURITY = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    public TreasuryBond {
        if (!maturityDate.isAfter(datedDate)) {
            throw new IllegalArgumentException("Maturity must be after dated date for " + cusip);
        }
    }

    @Override
    public String id() {
        return cusip;
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.TREASURY_BOND;
    }

    @Override
    public String currency() {
        return "USD";
    }

    /** The Valuation Date, and the Pillars around each cash flow still to be paid. */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        LocalDate valuationDate = market.valuationDate();
        Set<RiskFactorId> factors = new LinkedHashSet<>();
        factors.add(RiskFactorId.valuationDate(currency()));
        for (CashFlow cashFlow : cashFlows()) {
            if (cashFlow.date().isAfter(valuationDate)) {
                for (Pillar pillar : Pillar.around(pillars, YearFractions.act365(valuationDate, cashFlow.date()))) {
                    factors.add(RiskFactorId.pillarZeroRate(currency(), pillar));
                }
            }
        }
        return factors;
    }

    @Override
    public String description() {
        return String.format(Locale.US, "UST %.3f%% %s", couponRate * 100, maturityDate.format(MATURITY));
    }

    @Override
    public double dirtyValue(MarketState market) {
        LocalDate valuationDate = market.valuationDate();
        double value = 0;
        for (CashFlow cashFlow : cashFlows()) {
            if (cashFlow.date().isAfter(valuationDate)) {
                value += cashFlow.amount() * market.curve(currency()).discountFactor(
                        YearFractions.act365(valuationDate, cashFlow.date()));
            }
        }
        return value;
    }

    @Override
    public List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to) {
        return cashFlows().stream()
                .filter(c -> c.date().isAfter(from) && !c.date().isAfter(to))
                .toList();
    }

    /** Every coupon, then the redemption of principal at maturity. Dates are unadjusted. */
    public List<CashFlow> cashFlows() {
        return schedule().cashFlows(couponRate, currency());
    }

    /** Accrued interest per unit of notional: ACT/ACT (ICMA) within the regular period. */
    @Override
    public double accruedInterest(LocalDate valuationDate) {
        return schedule().accrualPeriod(valuationDate)
                .map(period -> couponRate / 2
                        * ChronoUnit.DAYS.between(period.accrualStart(), valuationDate)
                        / ChronoUnit.DAYS.between(period.regularStart(), period.nextCoupon()))
                .orElse(0.0);
    }

    /** Clean value per unit of notional. */
    public double cleanValue(MarketState market) {
        return dirtyValue(market) - accruedInterest(market.valuationDate());
    }

    /** Coupon payment dates after the dated date, ascending, ending with maturity. */
    public List<LocalDate> couponDates() {
        return schedule().couponDates();
    }

    private SemiAnnualSchedule schedule() {
        return new SemiAnnualSchedule(datedDate, maturityDate);
    }
}
