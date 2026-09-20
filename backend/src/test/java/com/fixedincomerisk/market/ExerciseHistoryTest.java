package com.fixedincomerisk.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * An Exercise Decision is recorded once and never recomputed. A decision that is recomputed every Tick
 * is not a decision, it is a comparison — and it would flip the moment rates crossed back through the
 * strike.
 */
class ExerciseHistoryTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 10, 11);

    @Test
    void theFirstDecisionRecordedForASwaptionAndExpiryStands() {
        ExerciseHistory history = new ExerciseHistory();

        assertThat(history.record("SWPN-1Mx5Y-PAY", EXPIRY, true)).isTrue();
        assertThat(history.decisions().wasExercised("SWPN-1Mx5Y-PAY", EXPIRY)).isTrue();

        // Rates crossed back through the strike, and something tried to decide again.
        assertThat(history.record("SWPN-1Mx5Y-PAY", EXPIRY, false)).isFalse();
        assertThat(history.record("SWPN-1Mx5Y-PAY", EXPIRY, false)).isFalse();

        assertThat(history.decisions().wasExercised("SWPN-1Mx5Y-PAY", EXPIRY)).isTrue();
        assertThat(history.decisions().keys())
                .containsExactly(new ExerciseDecisions.Key("SWPN-1Mx5Y-PAY", EXPIRY));
    }

    /** "Not exercised" is a recorded decision, and it is as immovable as the other one. */
    @Test
    void aRecordedLapseIsAlsoFinal() {
        ExerciseHistory history = new ExerciseHistory();

        assertThat(history.record("SWPN-1Mx5Y-PAY", EXPIRY, false)).isTrue();
        assertThat(history.record("SWPN-1Mx5Y-PAY", EXPIRY, true)).isFalse();

        assertThat(history.decisions().wasExercised("SWPN-1Mx5Y-PAY", EXPIRY)).isFalse();
    }

    /** Keyed by both: the same swaption at a different Expiry is a different decision. */
    @Test
    void decisionsAreKeyedBySwaptionAndExpiry() {
        ExerciseHistory history = new ExerciseHistory();
        history.record("SWPN-1Mx5Y-PAY", EXPIRY, true);
        history.record("SWPN-1Mx5Y-PAY", EXPIRY.plusYears(1), false);
        history.record("SWPN-1Yx10Y-REC", EXPIRY, false);

        ExerciseDecisions decisions = history.decisions();

        assertThat(decisions.wasExercised("SWPN-1Mx5Y-PAY", EXPIRY)).isTrue();
        assertThat(decisions.wasExercised("SWPN-1Mx5Y-PAY", EXPIRY.plusYears(1))).isFalse();
        assertThat(decisions.wasExercised("SWPN-1Yx10Y-REC", EXPIRY)).isFalse();
        assertThat(decisions.keys()).hasSize(3);
    }

    /** The view is a snapshot: recording more afterwards does not change one already taken. */
    @Test
    void theDecisionsViewIsASnapshot() {
        ExerciseHistory history = new ExerciseHistory();
        history.record("SWPN-1Mx5Y-PAY", EXPIRY, true);
        ExerciseDecisions before = history.decisions();

        history.record("SWPN-1Yx10Y-REC", EXPIRY, false);

        assertThat(before.keys()).hasSize(1);
        assertThat(history.decisions().keys()).hasSize(2);
        assertThat(ExerciseDecisions.NONE.isEmpty()).isTrue();
    }

    @Test
    void anUndecidedSwaptionFailsNamingItselfRatherThanReadingAsALapse() {
        assertThat(ExerciseDecisions.NONE.on("SWPN-1Mx5Y-PAY", EXPIRY)).isEmpty();
        assertThatThrownBy(() -> ExerciseDecisions.NONE.wasExercised("SWPN-1Mx5Y-PAY", EXPIRY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Exercise Decision recorded for SWPN-1Mx5Y-PAY")
                .hasMessageContaining("2026-10-11");
    }
}
