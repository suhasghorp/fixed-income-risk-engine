package com.fixedincomerisk.session;

import com.fixedincomerisk.session.RiskSnapshot.BookRisk;
import com.fixedincomerisk.session.RiskSnapshot.CreditView;
import com.fixedincomerisk.session.RiskSnapshot.CtdSwitchEvent;
import com.fixedincomerisk.session.RiskSnapshot.CurvePoint;
import com.fixedincomerisk.session.RiskSnapshot.FuturesView;
import com.fixedincomerisk.session.RiskSnapshot.LifecycleEvent;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.session.RiskSnapshot.RepricingTelemetry;
import com.fixedincomerisk.session.RiskSnapshot.SwapView;
import java.util.List;

/**
 * An incremental message carrying only what changed in one cycle, stamped with the Tick it was
 * priced against. See {@link RiskSnapshot#withUpdate} for how it applies.
 *
 * @param sequence              strictly increasing, one per Risk Update
 * @param ticksUntilDayRollover Ticks still to go before the next Day Rollover
 * @param valuationDate         the new Valuation Date, or null if unchanged
 * @param positions             the Position results that were repriced this cycle, and only those
 * @param bookRisk              the new Book rollups, or null if unchanged
 * @param curve                 the new curve points, or null if unchanged
 * @param lifecycleEvents       coupons and redemptions processed this cycle; only a Day Rollover has any
 * @param telemetry             what this cycle repriced, and how stale the rest is
 * @param futures               every futures contract's market state (its Basis moves every Tick)
 * @param ctdSwitches           CTD Switches that fired this cycle
 * @param credit                the credit market's observable state and Marks (the factors move every Tick)
 * @param swaps                 every swap's current period and Fixing, on a Day Rollover; null if unchanged
 */
public record RiskUpdate(
        long sequence,
        long tick,
        int ticksUntilDayRollover,
        String valuationDate,
        List<PositionResult> positions,
        BookRisk bookRisk,
        CurveChange curve,
        List<LifecycleEvent> lifecycleEvents,
        RepricingTelemetry telemetry,
        List<FuturesView> futures,
        List<CtdSwitchEvent> ctdSwitches,
        CreditView credit,
        List<SwapView> swaps) {

    public RiskUpdate {
        positions = List.copyOf(positions);
        lifecycleEvents = List.copyOf(lifecycleEvents);
        futures = List.copyOf(futures);
        ctdSwitches = List.copyOf(ctdSwitches);
        swaps = swaps == null ? null : List.copyOf(swaps);
    }

    public record CurveChange(List<CurvePoint> pillars, List<CurvePoint> points) {
    }
}
