package com.fixedincomerisk.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The FX Fixing store: the same discipline as {@link FixingHistory}, on a different concept. An
 * interest-rate Fixing sets a coupon; an FX Fixing sets a cash settlement.
 */
class FxFixingHistoryTest {

    private static final LocalDate FIXING_DATE = LocalDate.of(2026, 10, 9);

    private final FxFixingHistory history = new FxFixingHistory();

    @Test
    void recordsTheFirstRateForAPairAndDate() {
        assertThat(history.record("USDKRW", FIXING_DATE, 1400.0)).isTrue();

        assertThat(history.fixings().rate("USDKRW", FIXING_DATE)).isEqualTo(1400.0);
        assertThat(history.fixings().on("USDKRW", FIXING_DATE)).contains(1400.0);
    }

    /** Once struck, a settlement is not restruck: the second write is refused, not applied. */
    @Test
    void reRecordingTheSamePairAndDateLeavesTheOriginalUntouched() {
        history.record("USDKRW", FIXING_DATE, 1400.0);

        assertThat(history.record("USDKRW", FIXING_DATE, 1200.0)).isFalse();
        assertThat(history.fixings().rate("USDKRW", FIXING_DATE)).isEqualTo(1400.0);
    }

    @Test
    void differentPairsAndDatesAreDifferentFixings() {
        history.record("USDKRW", FIXING_DATE, 1400.0);
        history.record("EURUSD", FIXING_DATE, 1.15);
        history.record("USDKRW", FIXING_DATE.plusDays(1), 1401.0);

        assertThat(history.fixings().rate("USDKRW", FIXING_DATE)).isEqualTo(1400.0);
        assertThat(history.fixings().rate("EURUSD", FIXING_DATE)).isEqualTo(1.15);
        assertThat(history.fixings().rate("USDKRW", FIXING_DATE.plusDays(1))).isEqualTo(1401.0);
        assertThat(history.fixings().keys()).hasSize(3);
    }

    /** The view is a snapshot: recording more later does not reach back into one already handed out. */
    @Test
    void theFixingsViewIsImmutableAndDoesNotSeeLaterRecordings() {
        history.record("USDKRW", FIXING_DATE, 1400.0);
        FxFixings taken = history.fixings();

        history.record("USDKRW", FIXING_DATE.plusDays(1), 1401.0);

        assertThat(taken.keys()).hasSize(1);
        assertThat(taken.on("USDKRW", FIXING_DATE.plusDays(1))).isEmpty();
    }

    @Test
    void anUnrecordedFixingFailsRatherThanDefaulting() {
        assertThatThrownBy(() -> history.fixings().rate("USDKRW", FIXING_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FX Fixing recorded for USDKRW on 2026-10-09");
        assertThat(history.fixings().on("USDKRW", FIXING_DATE)).isEmpty();
        assertThat(FxFixings.NONE.isEmpty()).isTrue();
    }

    @Test
    void aFixingMustBeAPositiveRate() {
        assertThatThrownBy(() -> history.record("USDKRW", FIXING_DATE, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
        assertThatThrownBy(() -> history.record("USDKRW", FIXING_DATE, -1400))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be positive");
    }
}
