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
 * A non-deliverable forward: no currency is exchanged, and one net amount settles in the deliverable
 * currency after an FX Fixing.
 *
 * <p>Its forward rate is <em>quoted</em>, not derived: spot plus Forward Points, with the points simulated
 * as their own Risk Factor. There is no curve for the non-deliverable currency to derive a forward from,
 * which is precisely why its market quotes points instead — ADR-0009.
 *
 * <p>Settling in the base currency, per unit of base-currency notional, for a buyer of the base currency:
 *
 * <pre>settlement = 1 − K / F_fix,    V = (1 − K / F) · P_settle(T)</pre>
 *
 * <p>where K is the contracted rate, F_fix the rate recorded on the fixing date and F the quoted forward
 * standing in for it beforehand. Using the forward as the expected fixing ignores the convexity of 1/F; a
 * desk applies a small adjustment for it, and this engine deliberately does not.
 *
 * <p>It has DV01 in the settlement currency's curve only, plus a Forward Points delta. The outright has
 * the mirror image: two curves and no points. That difference is the checkable statement of how the two
 * markets differ.
 *
 * @param contractRate K, in quote-currency units per unit of base currency
 */
public record FxNdf(String id, FxPair pair, FxDirection direction, double contractRate,
                    LocalDate fixingDate, LocalDate settlementDate) implements Instrument {

    private static final DateTimeFormatter SETTLES = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    public FxNdf {
        if (!(contractRate > 0)) {
            throw new IllegalArgumentException("Contract rate must be positive for " + id + ", got " + contractRate);
        }
        if (!pair.quotesPoints()) {
            throw new IllegalArgumentException(pair.pair() + " is deliverable: use an FxForward, whose forward "
                    + "is derived from two curves");
        }
        if (settlementDate.isBefore(fixingDate)) {
            throw new IllegalArgumentException("Settlement must be on or after the fixing date for " + id);
        }
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.FX_NDF;
    }

    /** The settlement currency: the deliverable side of the pair, which is its base. */
    @Override
    public String currency() {
        return pair.baseCurrency();
    }

    /** An NDF is struck on the deliverable amount, which is also what it settles in. */
    @Override
    public String notionalCurrency() {
        return pair.baseCurrency();
    }

    @Override
    public boolean requiresPositiveQuantity() {
        return true;
    }

    @Override
    public String description() {
        return String.format(Locale.US, "%s %s NDF at %.2f, settles %s",
                direction == FxDirection.BUY_BASE ? "Buy" : "Sell", pair.pair(), contractRate,
                settlementDate.format(SETTLES));
    }

    /** The Valuation Date, FX Spot, the Forward Points, and the settlement curve's Pillars. */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        Set<RiskFactorId> factors = new LinkedHashSet<>();
        factors.add(RiskFactorId.valuationDate(currency()));
        if (!settlementDate.isAfter(market.valuationDate())) {
            return factors;
        }
        // Once the rate has fixed, neither spot nor the points move the settlement amount any more: all
        // that is left is discounting it.
        if (market.valuationDate().isBefore(fixingDate)) {
            factors.add(pair.spotFactor());
            factors.add(pair.pointsFactor());
        }
        double years = YearFractions.act365(market.valuationDate(), settlementDate);
        for (Pillar pillar : Pillar.around(pillars, years)) {
            factors.add(RiskFactorId.pillarZeroRate(currency(), pillar));
        }
        return factors;
    }

    /** Value per unit of notional, in the settlement currency; zero once settled. */
    @Override
    public double dirtyValue(MarketState market) {
        if (!settlementDate.isAfter(market.valuationDate())) {
            return 0;
        }
        double years = YearFractions.act365(market.valuationDate(), settlementDate);
        return settlementPerUnit(market) * market.curve(currency()).discountFactor(years);
    }

    @Override
    public java.util.Optional<String> fxPair() {
        return java.util.Optional.of(pair.pair());
    }

    /** The quoted forward: spot plus Forward Points, the points converted from pips at the pair's pip size. */
    public double forwardRate(MarketState market) {
        return market.fx().spot(pair.pair()) + market.fx().points(pair.pair()) * pair.pipSize();
    }

    /**
     * The rate the settlement is struck on: the quoted forward until the fixing date, and the FX Fixing
     * recorded on it from then on.
     *
     * <p>Once fixed, the settlement amount is known and stops moving with spot — which is why this reads
     * the recorded Fixing and fails if there is none, rather than quietly falling back to today's spot.
     */
    public double settlementRate(MarketState market) {
        return market.valuationDate().isBefore(fixingDate)
                ? forwardRate(market)
                : market.fx().fixings().rate(pair.pair(), fixingDate);
    }

    /** The net settlement per unit of notional, signed for the holder. */
    public double settlementPerUnit(MarketState market) {
        return direction.sign * (1 - contractRate / settlementRate(market));
    }

    /** One net payment in the settlement currency, on the settlement date. */
    @Override
    public List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to) {
        if (!settlementDate.isAfter(from) || settlementDate.isAfter(to)) {
            return List.of();
        }
        return List.of(new CashFlow(settlementDate, CashFlow.Kind.FX_SETTLEMENT,
                settlementPerUnit(market), currency()));
    }
}
