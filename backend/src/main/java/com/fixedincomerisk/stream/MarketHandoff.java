package com.fixedincomerisk.stream;

import com.fixedincomerisk.session.MarketTick;
import com.fixedincomerisk.session.MarketTicks;
import org.springframework.stereotype.Component;

/**
 * The single-slot, latest-value hand-off between the simulation thread and the repricing thread. A queue
 * of every Tick would let repricing fall ever further behind, and slowing the simulation would tie the
 * market to compute load; merging instead keeps risk on the newest market. The simulation thread publishes every Tick without ever waiting; if the repricing
 * thread has not taken the slot yet, the new Tick is merged into it, so the next cycle prices only the
 * newest market while keeping every merged Tick's events. Each Tick is immutable, so nothing is shared
 * mutably across the threads.
 */
@Component
class MarketHandoff {

    private MarketTicks pending;

    /** Called by the simulation thread: never blocks on repricing. */
    synchronized void publish(MarketTick tick) {
        pending = pending == null ? MarketTicks.of(tick) : pending.plus(tick);
        notifyAll();
    }

    /** Called by the repricing thread: waits for at least one Tick, then takes everything published since. */
    synchronized MarketTicks takeLatest() throws InterruptedException {
        while (pending == null) {
            wait();
        }
        MarketTicks taken = pending;
        pending = null;
        return taken;
    }
}
