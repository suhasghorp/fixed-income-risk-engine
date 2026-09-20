package com.fixedincomerisk.market;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session's Exercise Decisions: whether each Swaption was exercised, recorded on the Day Rollover
 * onto its Expiry from that day's curve. The first decision recorded for a (swaption, Expiry) is kept
 * forever, the same discipline {@link FixingHistory} and {@link FxFixingHistory} keep — so an option that
 * expired in the money stays exercised even if rates move back through the strike on the next Tick.
 *
 * <p>There is no un-exercising and no re-deciding. That is the whole point of recording it rather than
 * recomputing it: a decision that is recomputed every Tick is not a decision, it is a comparison.
 */
public final class ExerciseHistory {

    private final Map<ExerciseDecisions.Key, Boolean> decisions = new LinkedHashMap<>();

    /**
     * Records whether {@code swaptionId} was exercised at {@code expiry}, unless a decision is already
     * recorded.
     *
     * @return true if recorded, false if that (swaption, Expiry) had already decided, which stands
     */
    public boolean record(String swaptionId, LocalDate expiry, boolean exercised) {
        return decisions.putIfAbsent(new ExerciseDecisions.Key(swaptionId, expiry), exercised) == null;
    }

    public ExerciseDecisions decisions() {
        return new ExerciseDecisions(decisions);
    }
}
