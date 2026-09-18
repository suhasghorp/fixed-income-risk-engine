package com.fixedincomerisk.instrument;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.MarketState.FuturesMarket;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A Treasury futures contract, priced as F = P_proxy / CF + B: its current Proxy Bond's
 * clean price off the curve, divided by that bond's conversion factor, plus the Basis in price points.
 * Which Proxy Bond is current and the Basis level come from the market; a CTD Switch changes both.
 *
 * <p>Modelled as a continuous front contract: expiry, delivery and rolling are out of scope.
 *
 * @param contract     contract id, e.g. "ZNZ6", used as the Instrument id
 * @param description  e.g. "ZN Dec26 10Y note future"
 * @param deliverables the Proxy Bonds a CTD Switch moves between; the first is the initial CTD
 */
public record TreasuryFuture(String contract, String description, List<ProxyBond> deliverables)
        implements Instrument {

    public TreasuryFuture {
        deliverables = List.copyOf(deliverables);
        if (deliverables.size() < 2) {
            throw new IllegalArgumentException("A future needs at least two Proxy Bonds to switch between: " + contract);
        }
    }

    @Override
    public String id() {
        return contract;
    }

    @Override
    public InstrumentType type() {
        return InstrumentType.TREASURY_FUTURE;
    }

    @Override
    public String currency() {
        return "USD";
    }

    @Override
    public boolean marginedDaily() {
        return true;
    }

    /**
     * The Valuation Date, the Basis, the choice of Proxy Bond, and the Pillars around every deliverable's
     * remaining cash flows, so the dependencies hold whichever bond is the CTD.
     */
    @Override
    public Set<RiskFactorId> riskFactors(MarketState market, List<Pillar> pillars) {
        Set<RiskFactorId> factors = new LinkedHashSet<>();
        factors.add(RiskFactorId.basis(currency(), contract));
        factors.add(RiskFactorId.proxyBond(currency(), contract));
        for (ProxyBond deliverable : deliverables) {
            factors.addAll(deliverable.bond().riskFactors(market, pillars));
        }
        return factors;
    }

    /** The futures price per unit of face: F / 100. */
    @Override
    public double dirtyValue(MarketState market) {
        FuturesMarket state = market.futures(contract);
        ProxyBond proxy = currentProxy(state);
        return proxy.bond().cleanValue(market) / proxy.conversionFactor() + state.basis() / 100;
    }

    /** Futures pay no coupons; gains and losses are margined daily. */
    @Override
    public List<CashFlow> cashFlowsPaid(MarketState market, LocalDate from, LocalDate to) {
        return List.of();
    }

    public ProxyBond currentProxy(FuturesMarket state) {
        return deliverables.get(state.proxyIndex());
    }
}
