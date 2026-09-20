package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.FxPair;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A deliverable FX outright: both currencies are exchanged in full at maturity, at a rate agreed today.
 *
 * <p>Its forward rate is <em>derived</em>, never quoted. Value per unit of base-currency notional, in the
 * quote currency, for a buyer of the base currency:
 *
 * <pre>V = S · P_base(T) − K · P_quote(T)</pre>
 *
 * <p>which is zero exactly when K is the covered-interest-parity forward S · P_base(T) / P_quote(T). That
 * identity is the anchor test for this Instrument, and it is also what makes the outright the series'
 * example of a price the market derives rather than observes — see ADR-0009.
 *
 * <p>It therefore has DV01 in <em>both</em> curves and no Forward Points delta: a deliverable currency has
 * a government curve to build and a funding market that enforces parity.
 *
 * @param contractRate K, in quote-currency units per unit of base currency
 */
public record FxForward(String id, FxPair pair, FxDirection direction, double contractRate,
                        LocalDate maturityDate) implements Instrument {

    private static final DateTimeFormatter MATURITY = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    public FxForward {
        if (!(contractRate > 0)) {
            throw new IllegalArgumentException("Contract rate must be positive for " + id + ", got " + contractRate);
        }
        if (pair.quotesPoints()) {
            throw new IllegalArgumentException(pair.pair() + " is non-deliverable: use an FxNdf, whose forward "
                    + "is quoted rather than derived");
        }
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.FX_FORWARD;
    }

    /** Both legs are exchanged, so the value lands in the quote currency. */
    @Override
    public String currency() {
        return pair.quoteCurrency();
    }

    /** A deliverable outright is struck on the base-currency amount. */
    @Override
    public String notionalCurrency() {
        return pair.baseCurrency();
    }

    /** Which side the holder is on is part of the terms, so the Position quantity is the notional. */
    @Override
    public boolean requiresPositiveQuantity() {
        return true;
    }

    @Override
    public String description() {
        return String.format(Locale.US, "%s %s at %.4f to %s",
                direction == FxDirection.BUY_BASE ? "Buy" : "Sell", pair.pair(), contractRate,
                maturityDate.format(MATURITY));
    }

    /** The Valuation Date, FX Spot, and the Pillars around maturity on <em>both</em> currencies' curves. */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        Set<RiskFactorId> factors = new LinkedHashSet<>();
        factors.add(RiskFactorId.valuationDate(currency()));
        if (!maturityDate.isAfter(market.valuationDate())) {
            return factors;
        }
        factors.add(pair.spotFactor());
        double years = YearFractions.act365(market.valuationDate(), maturityDate);
        for (Pillar pillar : Pillar.around(pillars, years)) {
            factors.add(RiskFactorId.pillarZeroRate(pair.baseCurrency(), pillar));
            factors.add(RiskFactorId.pillarZeroRate(pair.quoteCurrency(), pillar));
        }
        return factors;
    }

    /** Value per unit of base-currency notional, in the quote currency; zero once matured. */
    @Override
    public double dirtyValue(MarketState market) {
        if (!maturityDate.isAfter(market.valuationDate())) {
            return 0;
        }
        double years = YearFractions.act365(market.valuationDate(), maturityDate);
        double baseLeg = market.fx().spot(pair.pair())
                * market.curve(pair.baseCurrency()).discountFactor(years);
        double quoteLeg = contractRate * market.curve(pair.quoteCurrency()).discountFactor(years);
        return direction.sign * (baseLeg - quoteLeg);
    }

    @Override
    public java.util.Optional<String> fxPair() {
        return java.util.Optional.of(pair.pair());
    }

    /** The forward rate the two curves and spot imply: S · P_base(T) / P_quote(T). */
    public double forwardRate(MarketState market) {
        double years = YearFractions.act365(market.valuationDate(), maturityDate);
        return market.fx().spot(pair.pair())
                * market.curve(pair.baseCurrency()).discountFactor(years)
                / market.curve(pair.quoteCurrency()).discountFactor(years);
    }

    /** Both legs settle at maturity: two Lifecycle Events, one per currency. */
    @Override
    public List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to) {
        if (!maturityDate.isAfter(from) || maturityDate.isAfter(to)) {
            return List.of();
        }
        return List.of(
                new CashFlow(maturityDate, CashFlow.Kind.FX_LEG, direction.sign, pair.baseCurrency()),
                new CashFlow(maturityDate, CashFlow.Kind.FX_LEG, -direction.sign * contractRate,
                        pair.quoteCurrency()));
    }
}
