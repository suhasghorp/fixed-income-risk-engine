package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recorded Exercise Decisions, by swaption and Expiry: whether an option was exercised, decided once on
 * its Expiry date and never revisited. An immutable view of the session's {@link ExerciseHistory}.
 *
 * <p>Distinct from a Lifecycle Event, and deliberately not one. A Lifecycle Event is a cash flow a
 * Position receives or pays; an exercise pays nothing — it changes what the Position <em>is</em>. The FX
 * work drew the same line between an FX Fixing and a Fixing rather than stretching one word over two
 * ideas.
 *
 * @param exercised true where the option was exercised, by (swaption id, Expiry)
 */
public record ExerciseDecisions(Map<Key, Boolean> exercised) {

    public static final ExerciseDecisions NONE = new ExerciseDecisions(Map.of());

    /** @param swaptionId the Swaption's own id, not its Position's or its underlying swap's */
    public record Key(String swaptionId, LocalDate expiry) {
    }

    public ExerciseDecisions {
        exercised = Collections.unmodifiableMap(new LinkedHashMap<>(exercised));
    }

    public Optional<Boolean> on(String swaptionId, LocalDate expiry) {
        return Optional.ofNullable(exercised.get(new Key(swaptionId, expiry)));
    }

    /**
     * Whether the option was exercised; fails rather than defaulting to "no", because an option past its
     * Expiry with no decision recorded is a bug in the simulation and not an option that expired
     * worthless. The two are worth very different amounts.
     */
    public boolean wasExercised(String swaptionId, LocalDate expiry) {
        return on(swaptionId, expiry).orElseThrow(() -> new IllegalStateException(
                "No Exercise Decision recorded for " + swaptionId + " expiring " + expiry
                        + "; recorded " + keys()));
    }

    public List<Key> keys() {
        return List.copyOf(exercised.keySet());
    }

    public boolean isEmpty() {
        return exercised.isEmpty();
    }
}
