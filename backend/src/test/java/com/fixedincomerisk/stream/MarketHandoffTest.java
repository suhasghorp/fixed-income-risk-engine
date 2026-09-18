package com.fixedincomerisk.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fixedincomerisk.session.MarketTick;
import com.fixedincomerisk.session.MarketTicks;
import com.fixedincomerisk.session.RiskSnapshot.CtdSwitchEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketHandoffTest {

    /** A bare Tick: the hand-off only cares about ordering, rollovers and events. */
    private static MarketTick tick(long n, boolean dayRollover) {
        List<CtdSwitchEvent> switches = n % 3 == 0 ? List.of(new CtdSwitchEvent(n, "ZNZ6", "A", "B", 0.15)) : List.of();
        return new MarketTick(n, 0, null, dayRollover, null, List.of(), null, List.of(), List.of(), switches, null);
    }

    @Test
    void ticksPublishedBeforeATakeAreMergedWithTheNewestKept() throws InterruptedException {
        MarketHandoff handoff = new MarketHandoff();
        for (long n = 1; n <= 5; n++) {
            handoff.publish(tick(n, n == 2));
        }

        MarketTicks taken = handoff.takeLatest();

        assertThat(taken.latest().tick()).isEqualTo(5);
        assertThat(taken.count()).isEqualTo(5);
        assertThat(taken.coalesced()).isEqualTo(4);
        assertThat(taken.dayRollover()).as("a rollover on any merged tick counts").isTrue();
        assertThat(taken.ctdSwitches()).extracting(CtdSwitchEvent::tick).containsExactly(3L);

        handoff.publish(tick(6, false));
        assertThat(handoff.takeLatest().count()).as("the slot empties on take").isEqualTo(1);
    }

    @Test
    void aSlowConsumerNeverHoldsUpTheProducerAndLosesNoTicks() {
        MarketHandoff handoff = new MarketHandoff();
        int total = 2_000;
        List<MarketTicks> taken = new ArrayList<>();

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Thread consumer = Thread.ofPlatform().start(() -> {
                try {
                    long seen = 0;
                    while (seen < total) {
                        MarketTicks ticks = handoff.takeLatest();
                        taken.add(ticks);
                        seen = ticks.latest().tick();
                        Thread.sleep(2); // a slow repricing cycle
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            long start = System.nanoTime();
            for (long n = 1; n <= total; n++) {
                handoff.publish(tick(n, false));
            }
            Duration producing = Duration.ofNanos(System.nanoTime() - start);
            consumer.join();

            assertThat(producing).as("publishing never waits for the consumer").isLessThan(Duration.ofSeconds(2));
        });

        assertThat(taken.stream().mapToInt(MarketTicks::count).sum()).as("every tick accounted for").isEqualTo(total);
        assertThat(taken.getLast().latest().tick()).isEqualTo(total);
        assertThat(taken.stream().mapToInt(MarketTicks::coalesced).sum()).as("the slow consumer coalesced").isPositive();
        assertThat(taken.stream().flatMap(t -> t.ctdSwitches().stream()).count()).isEqualTo(total / 3);
    }
}
