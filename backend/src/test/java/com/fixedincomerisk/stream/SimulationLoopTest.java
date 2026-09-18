package com.fixedincomerisk.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.RepricingSettings;
import com.fixedincomerisk.session.MarketTicks;
import com.fixedincomerisk.session.RiskSession;
import com.fixedincomerisk.session.SessionConfig;
import com.fixedincomerisk.simulation.SimulationSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulationLoopTest {

    private static RiskSession session(long stopAtTick) {
        return RiskSession.create(new SessionConfig(
                new BundledCurveSource(),
                ReferenceData.fromClasspath(),
                new HullWhiteParameters(0.05, 0.01),
                new FuturesBasisParameters(12, -0.2, 0.5, 24, 0.15),
                new CreditParameters(0.5, 60, 40, 2, 25, 20, 15),
                CreditEventParameters.NONE,
                CorrelationMatrix.identity(),
                Pillar.DEFAULTS,
                new SimulationSettings(42, Duration.ofHours(1), 24, stopAtTick),
                RepricingSettings.REPRICE_EVERYTHING));
    }

    @Test
    void theSessionRefusesToAdvancePastItsStopTick() {
        try (RiskSession session = session(3)) {
            session.step();
            session.step();
            assertThat(session.canAdvance()).isTrue();
            session.step();

            assertThat(session.canAdvance()).isFalse();
            assertThat(session.snapshot().tick()).isEqualTo(3);
            assertThat(session.snapshot().session().stopAtTick()).isEqualTo(3L);
            assertThatThrownBy(session::advanceMarket).hasMessageContaining("stopped at tick 3");
        }
        try (RiskSession unstopped = session(0)) {
            assertThat(unstopped.snapshot().session().stopAtTick()).isNull();
        }
    }

    @Test
    void theSimulationThreadHaltsAfterPublishingTheStopTick() {
        try (RiskSession session = session(5)) {
            MarketHandoff handoff = new MarketHandoff();
            SimulationLoop loop = new SimulationLoop(session, handoff, Duration.ofMillis(5));

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                loop.start();
                long latest = 0;
                while (latest < 5) {
                    MarketTicks ticks = handoff.takeLatest();
                    latest = ticks.latest().tick();
                }
                Thread.sleep(100); // many intervals: nothing more may arrive
                assertThat(latest).isEqualTo(5);
                assertThat(session.canAdvance()).isFalse();
            });
            loop.stop();
        }
    }
}
