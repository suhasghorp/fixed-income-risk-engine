package com.fixedincomerisk.stream;

import com.fixedincomerisk.session.RiskSession;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The simulation thread: advances the market one Tick per wall-clock interval and publishes
 * it into the hand-off. It never prices, so it keeps ticking at the configured interval however long
 * repricing takes. With a stop-at-tick it halts after publishing that Tick; the stream stays open.
 */
@Component
class SimulationLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulationLoop.class);

    private final RiskSession session;
    private final MarketHandoff handoff;
    private final Duration tickInterval;
    private volatile ScheduledExecutorService executor;

    SimulationLoop(RiskSession session, MarketHandoff handoff,
                   @Value("${risk.simulation.tick-interval}") Duration tickInterval) {
        this.session = session;
        this.handoff = handoff;
        this.tickInterval = tickInterval;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("simulation").daemon().factory());
        long millis = tickInterval.toMillis();
        executor.scheduleAtFixedRate(this::tick, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Simulation thread ticking every {}", tickInterval);
    }

    private void tick() {
        try {
            if (!session.canAdvance()) {
                log.info("Simulation stopped at the configured stop-at-tick; the last state stays on screen");
                executor.shutdown();
                return;
            }
            handoff.publish(session.advanceMarket());
        } catch (RuntimeException e) {
            // Never let one bad tick cancel the schedule.
            log.error("Tick failed", e);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService running = executor;
        if (running != null) {
            running.shutdownNow();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
