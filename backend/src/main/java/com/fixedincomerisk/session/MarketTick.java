package com.fixedincomerisk.session;

import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.model.CorrelatedShockGenerator;
import com.fixedincomerisk.session.RiskSnapshot.CreditView;
import com.fixedincomerisk.session.RiskSnapshot.CtdSwitchEvent;
import com.fixedincomerisk.session.RiskSnapshot.CurveView;
import com.fixedincomerisk.session.RiskSnapshot.FuturesView;
import com.fixedincomerisk.session.RiskSnapshot.LifecycleEvent;
import com.fixedincomerisk.session.RiskSnapshot.SwapView;
import java.util.List;

/**
 * One Tick of the simulated market, as published by the simulation side: fully immutable, so it can be
 * handed to the repricing thread and priced without ever seeing a partially updated state.
 *
 * @param market                the market to price against
 * @param dayRollover           whether this Tick rolled the Valuation Date over
 * @param curve                 the curve at the Pillars and chart points
 * @param lifecycleEvents       cash flows processed by this Tick's Day Rollover, if any
 * @param ctdSwitches           CTD Switches that fired on this Tick
 * @param shocks                the correlated shocks that drove this Tick, or null for the opening state
 */
public record MarketTick(
        long tick,
        int ticksUntilDayRollover,
        MarketState market,
        boolean dayRollover,
        CurveView curve,
        List<FuturesView> futures,
        CreditView credit,
        List<SwapView> swaps,
        List<LifecycleEvent> lifecycleEvents,
        List<CtdSwitchEvent> ctdSwitches,
        CorrelatedShockGenerator.Shocks shocks) {

    public MarketTick {
        futures = List.copyOf(futures);
        swaps = List.copyOf(swaps);
        lifecycleEvents = List.copyOf(lifecycleEvents);
        ctdSwitches = List.copyOf(ctdSwitches);
    }
}
