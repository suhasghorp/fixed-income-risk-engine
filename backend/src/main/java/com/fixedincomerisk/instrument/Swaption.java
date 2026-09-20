package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.market.SurfacePoint;
import com.fixedincomerisk.model.BachelierModel;
import com.fixedincomerisk.time.YearFractions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A European swaption: the right, on one date, to enter the swap it holds. Physically settled, which is
 * the USD market convention — after Exercise the Position holds a swap with DV01 rather than banking one
 * cash amount and going quiet.
 *
 * <p>It owns no terms of its own. The <em>strike</em> is the underlying's {@code fixedRate}, <em>payer
 * versus receiver</em> is the underlying's {@code Direction}, and the Expiry is the underlying's
 * {@code effectiveDate} — checked on construction. So there is no second direction enum and no duplicated
 * strike, and a swaption that disagrees with the swap it exercises into cannot be built. Everything the
 * option needs from the swap arrives as two numbers, the Annuity and the Forward Swap Rate, on the swap's
 * own conventions; restating those conventions here is how an option ends up pricing off one day count
 * and exercising into another.
 *
 * <p>Priced with {@link BachelierModel} off the Normal Volatility quoted at its own Surface Point, not
 * with the simulator's own Hull-White σ — ADR-0011.
 *
 * @param expiryDate   the date the Exercise Decision is made, and the date the underlying starts
 * @param surfacePoint the one point on the volatility surface this option prices from; there is no grid
 *                     and no interpolation, so it is a name and not a coordinate to look up
 */
public record Swaption(String id, LocalDate expiryDate, SurfacePoint surfacePoint, InterestRateSwap underlying)
        implements Instrument {

    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    public Swaption {
        if (!expiryDate.equals(underlying.effectiveDate())) {
            throw new IllegalArgumentException("Swaption " + id + " expires " + expiryDate
                    + " but exercises into a swap effective " + underlying.effectiveDate()
                    + "; the Expiry is the underlying's effective date");
        }
        if (!surfacePoint.currency().equals(underlying.currency())) {
            throw new IllegalArgumentException("Swaption " + id + " prices off Surface Point "
                    + surfacePoint.label() + " but exercises into a " + underlying.currency() + " swap");
        }
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.SWAPTION;
    }

    @Override
    public String currency() {
        return underlying.currency();
    }

    /** The quantity is the underlying's notional; paying or receiving fixed is part of the terms. */
    @Override
    public boolean requiresPositiveQuantity() {
        return true;
    }

    /** The strike: the fixed rate of the swap this option exercises into, and nothing else. */
    public double strike() {
        return underlying.fixedRate();
    }

    /** True if exercising means paying fixed, which is what makes this a payer swaption. */
    public boolean isPayer() {
        return underlying.direction() == InterestRateSwap.Direction.PAY_FIXED;
    }

    @Override
    public String description() {
        return String.format(Locale.US, "%s %s swaption at %.3f%%, expires %s",
                isPayer() ? "Payer" : "Receiver", surfacePoint.point(), strike() * 100,
                expiryDate.format(EXPIRES));
    }

    /**
     * Before Expiry: the Valuation Date, its own Surface Point's Normal Volatility, and the Pillars the
     * underlying swap is exposed to — taken from the swap itself, so the option depends on exactly the
     * curve it is priced off, at exactly the Pillars that carry it.
     *
     * <p>At the Expiry the Normal Volatility drops out either way, and the rest depends on the Exercise
     * Decision: an exercised Swaption keeps the underlying's Pillars, because it <em>is</em> the
     * underlying now; an unexercised one is worth zero and depends on nothing but the Valuation Date.
     * Dependencies changing at runtime is established; ADR-0003.
     */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        if (!market.valuationDate().isBefore(expiryDate)) {
            return wasExercised(market)
                    ? Set.copyOf(underlying.riskFactors(market, pillars))
                    : Set.of(RiskFactorId.valuationDate(currency()));
        }
        Set<RiskFactorId> factors = new LinkedHashSet<>(underlying.riskFactors(market, pillars));
        factors.add(surfacePoint.volFactor());
        return factors;
    }

    /** The recorded Exercise Decision; before the Expiry there is nothing to ask about. */
    public boolean wasExercised(MarketState market) {
        return !market.valuationDate().isBefore(expiryDate)
                && market.exercises().wasExercised(id, expiryDate);
    }

    /**
     * Before Expiry, the premium per unit of the underlying's notional: an option is bought, so it is
     * never negative whichever way the market has moved. From the Expiry it is the underlying swap if the
     * recorded Exercise Decision says so, and exactly zero if it does not — at which point it can and
     * does go negative, because it is a swap now and no longer an option.
     *
     * <p>The Position never moves and the Book never changes shape; what changed is what this Position
     * <em>is</em>. ADR-0012.
     */
    @Override
    public double dirtyValue(MarketState market) {
        if (!market.valuationDate().isBefore(expiryDate)) {
            return wasExercised(market) ? underlying.dirtyValue(market) : 0;
        }
        double annuity = underlying.annuity(market);
        if (annuity <= 0) {
            return 0;
        }
        return isPayer()
                ? BachelierModel.payer(annuity, underlying.forwardRate(market), strike(),
                        market.vols().normalVol(surfacePoint.label()), yearsToExpiry(market))
                : BachelierModel.receiver(annuity, underlying.forwardRate(market), strike(),
                        market.vols().normalVol(surfacePoint.label()), yearsToExpiry(market));
    }

    /** Vega per 1bp of this option's Surface Point, per unit of notional. Zero once there is no time left. */
    public double vegaPerBasisPoint(MarketState market) {
        if (!market.valuationDate().isBefore(expiryDate)) {
            return 0;
        }
        double annuity = underlying.annuity(market);
        if (annuity <= 0) {
            return 0;
        }
        return BachelierModel.vegaPerBasisPoint(annuity, underlying.forwardRate(market), strike(),
                market.vols().normalVol(surfacePoint.label()), yearsToExpiry(market));
    }

    /** Year fraction to the Expiry on ACT/365, the same time axis the curve discounts on. */
    private double yearsToExpiry(MarketState market) {
        return YearFractions.act365(market.valuationDate(), expiryDate);
    }

    /**
     * None before Expiry, and none for the Exercise itself: an Exercise pays nothing, which is why it is
     * not a Lifecycle Event. Once exercised the Position holds a swap, so the swap's own coupons flow as
     * ordinary Lifecycle Events from then on. ADR-0012.
     */
    @Override
    public List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to) {
        return wasExercised(market) ? underlying.cashFlowsPaid(market, from, to) : List.of();
    }

    /**
     * The underlying's. An option accrues nothing, and this signature carries no market to read the
     * Exercise Decision from — which costs nothing, because a swap in this engine accrues nothing either:
     * its coupons are Lifecycle Events, not accruals carried in the price.
     */
    @Override
    public double accruedInterest(LocalDate valuationDate) {
        return underlying.accruedInterest(valuationDate);
    }
}
