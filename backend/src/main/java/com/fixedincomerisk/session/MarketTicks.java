package com.fixedincomerisk.session;

import com.fixedincomerisk.session.RiskSnapshot.CtdSwitchEvent;
import com.fixedincomerisk.session.RiskSnapshot.LifecycleEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * One or more consecutive Ticks merged for a single repricing cycle (latest-wins coalescing):
 * only the newest market is priced, but nothing that happened in between is lost. Day Rollovers, lifecycle
 * events and CTD Switches from every merged Tick are carried into the cycle's one Risk Update.
 *
 * @param latest      the newest Tick: the only one priced
 * @param count       how many Ticks were merged; 1 when nothing was coalesced
 * @param dayRollover whether any merged Tick rolled the Valuation Date over
 */
public record MarketTicks(MarketTick latest, int count, boolean dayRollover, List<LifecycleEvent> lifecycleEvents,
                          List<CtdSwitchEvent> ctdSwitches) {

    public MarketTicks {
        lifecycleEvents = List.copyOf(lifecycleEvents);
        ctdSwitches = List.copyOf(ctdSwitches);
    }

    public static MarketTicks of(MarketTick tick) {
        return new MarketTicks(tick, 1, tick.dayRollover(), tick.lifecycleEvents(), tick.ctdSwitches());
    }

    /** These Ticks followed by {@code next}, which becomes the newest. */
    public MarketTicks plus(MarketTick next) {
        if (next.tick() != latest.tick() + 1) {
            throw new IllegalArgumentException("Tick " + next.tick() + " does not follow " + latest.tick());
        }
        List<LifecycleEvent> events = new ArrayList<>(lifecycleEvents);
        events.addAll(next.lifecycleEvents());
        List<CtdSwitchEvent> switches = new ArrayList<>(ctdSwitches);
        switches.addAll(next.ctdSwitches());
        return new MarketTicks(next, count + 1, dayRollover || next.dayRollover(), events, switches);
    }

    /** Ticks merged into this cycle beyond the one priced. */
    public int coalesced() {
        return count - 1;
    }
}
