package com.fixedincomerisk.stream;

import com.fixedincomerisk.session.MarketTicks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The repricing thread: repeatedly takes the newest market from the hand-off (coalescing any
 * Ticks it missed), reprices the dirty Instruments on the worker pool, aggregates, and publishes one Risk
 * Update. An optional artificial delay per cycle makes coalescing visible in a demo.
 */
@Component
class RepricingLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RepricingLoop.class);

    private final MarketHandoff handoff;
    private final LiveRiskStream stream;
    private final Duration cycleDelay;
    private volatile Thread thread;

    RepricingLoop(MarketHandoff handoff, LiveRiskStream stream,
                  @Value("${risk.repricing.cycle-delay:0ms}") Duration cycleDelay) {
        this.handoff = handoff;
        this.stream = stream;
        this.cycleDelay = cycleDelay;
    }

    @Override
    public void start() {
        thread = Thread.ofPlatform().name("repricing").daemon().start(this::run);
        if (!cycleDelay.isZero()) {
            log.info("Repricing slowed by {} per cycle: expect ticks to be coalesced", cycleDelay);
        }
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                MarketTicks ticks = handoff.takeLatest();
                stream.priceAndPublish(ticks);
                if (!cycleDelay.isZero()) {
                    Thread.sleep(cycleDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // One bad cycle must not stop repricing; the next cycle prices the newest market again.
                log.error("Repricing cycle failed", e);
            }
        }
    }

    @Override
    public void stop() {
        Thread running = thread;
        if (running != null) {
            running.interrupt();
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        return thread != null;
    }
}
