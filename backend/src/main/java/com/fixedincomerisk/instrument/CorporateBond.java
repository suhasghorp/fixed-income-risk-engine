package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A fixed-coupon corporate bond from a fictional issuer: semi-annual coupons, 30/360 US accrual. It is
 * priced off the Treasury curve plus its issuer's Mark, one flat continuously compounded Z-spread shared
 * by all the issuer's bonds: each cash flow at time t is discounted by DF(t)·e^(−Mark·t).
 * It never sees the issuer's Latent Spread.
 *
 * @param id         the Instrument id, e.g. "ACME-4.85-2031"
 * @param issuerId   the issuer whose Mark prices this bond
 * @param issuerName for display
 * @param couponRate annual coupon as a decimal
 */
public record CorporateBond(String id, String issuerId, String issuerName, double couponRate, LocalDate datedDate,
                            LocalDate maturityDate) implements Instrument {

    private static final DateTimeFormatter MATURITY = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    public CorporateBond {
        if (!maturityDate.isAfter(datedDate)) {
            throw new IllegalArgumentException("Maturity must be after dated date for " + id);
        }
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.CORPORATE_BOND;
    }

    @Override
    public String currency() {
        return "USD";
    }

    @Override
    public Optional<String> issuer() {
        return Optional.of(issuerId);
    }

    @Override
    public String description() {
        return String.format(Locale.US, "%s %.3f%% %s", issuerName, couponRate * 100, maturityDate.format(MATURITY));
    }

    /**
     * The Valuation Date, the issuer's Mark, the observable factors its Mark is Matrix-Priced from (the
     * Systemic Factor and the Sector Factor of the issuer's current Rating Bucket), its public rating, and
     * the Pillars around each cash flow still to be paid. A Rating Migration changes the Sector edge.
     */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        LocalDate valuationDate = market.valuationDate();
        Set<RiskFactorId> factors = new LinkedHashSet<>();
        factors.add(RiskFactorId.valuationDate(currency()));
        factors.add(RiskFactorId.mark(currency(), issuerId));
        factors.add(RiskFactorId.rating(currency(), issuerId));
        factors.add(RiskFactorId.systemic(currency()));
        factors.add(RiskFactorId.sector(currency(), market.credit().rating(issuerId)));
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
    public double dirtyValue(MarketState market) {
        LocalDate valuationDate = market.valuationDate();
        double spread = market.mark(issuerId);
        double value = 0;
        for (CashFlow cashFlow : cashFlows()) {
            if (cashFlow.date().isAfter(valuationDate)) {
                double t = YearFractions.act365(valuationDate, cashFlow.date());
                value += cashFlow.amount() * market.curve(currency()).discountFactor(t) * Math.exp(-spread * t);
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

    /** Accrued interest per unit of notional, 30/360 US from the start of the current period. */
    @Override
    public double accruedInterest(LocalDate valuationDate) {
        return schedule().accrualPeriod(valuationDate)
                .map(period -> couponRate * YearFractions.days30360(period.accrualStart(), valuationDate) / 360.0)
                .orElse(0.0);
    }

    public List<CashFlow> cashFlows() {
        return schedule().cashFlows(couponRate, currency());
    }

    private SemiAnnualSchedule schedule() {
        return new SemiAnnualSchedule(datedDate, maturityDate);
    }
}
