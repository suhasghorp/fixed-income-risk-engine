package com.fixedincomerisk.credit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps each issuer's Mark, the spread the risk engine prices with. On a Print or Quote
 * the Mark resets to the observed spread; a Print wins over a Quote on the same tick. Between
 * observations the Mark is carried forward by Matrix Pricing: each tick it moves by the change in the
 * Systemic Factor plus the change in the issuer's Sector Factor, and by nothing unobservable.
 *
 * <p>On a Rating Migration, which is public, Matrix Pricing re-bases the Mark onto the new bucket: it
 * moves from the old bucket's Sector level to the new one's. The Credit Event's spread jump is latent, so
 * the Mark stays stale until the next observation reveals it.
 */
public final class CreditMarker {

    private final Map<String, Double> marks = new LinkedHashMap<>();
    private final Map<String, Observed> lastPrints = new LinkedHashMap<>();
    private final Map<String, Observed> lastQuotes = new LinkedHashMap<>();
    private final Map<String, Migration> migrations = new LinkedHashMap<>();
    private CreditObservables previous;

    /**
     * Opening Marks are the previous close: the Systemic and Sector levels plus each issuer's
     * Idiosyncratic long-run mean, where the Idiosyncratic Factor starts.
     */
    public CreditMarker(CreditObservables opening, List<Issuer> issuers) {
        this.previous = opening;
        for (Issuer issuer : issuers) {
            marks.put(issuer.id(), opening.systemic() + opening.sector(opening.rating(issuer.id()))
                    + issuer.idiosyncraticMeanBp() * 1e-4);
        }
    }

    /** Applies one tick: resets Marks on observations, and Matrix-Prices the rest. */
    public void update(long tick, CreditObservables observables, List<CreditObservation> observations) {
        for (Map.Entry<String, Double> entry : marks.entrySet()) {
            String issuerId = entry.getKey();
            RatingBucket was = previous.rating(issuerId);
            RatingBucket now = observables.rating(issuerId);
            if (!now.equals(was)) {
                migrations.put(issuerId, new Migration(was, now, tick, true));
            }
            Optional<CreditObservation> observed = latest(observations, issuerId);
            if (observed.isPresent()) {
                entry.setValue(observed.get().spread());
                migrations.computeIfPresent(issuerId, (id, migration) -> migration.withMarkStale(false));
            } else {
                entry.setValue(entry.getValue()
                        + (observables.systemic() - previous.systemic())
                        + (observables.sector(now) - previous.sector(was)));
            }
        }
        for (CreditObservation observation : observations) {
            Map<String, Observed> last = observation.kind() == CreditObservation.Kind.PRINT ? lastPrints : lastQuotes;
            last.put(observation.issuerId(), new Observed(observation.spread(), tick));
        }
        previous = observables;
    }

    /** Each issuer's current Mark, by issuer id. */
    public Map<String, Double> marks() {
        return Map.copyOf(marks);
    }

    public Optional<Observed> lastPrint(String issuerId) {
        return Optional.ofNullable(lastPrints.get(issuerId));
    }

    public Optional<Observed> lastQuote(String issuerId) {
        return Optional.ofNullable(lastQuotes.get(issuerId));
    }

    /** The issuer's latest Rating Migration, if any. */
    public Optional<Migration> lastMigration(String issuerId) {
        return Optional.ofNullable(migrations.get(issuerId));
    }

    /** The Print if there is one this tick, otherwise the Quote. */
    private static Optional<CreditObservation> latest(List<CreditObservation> observations, String issuerId) {
        CreditObservation latest = null;
        for (CreditObservation observation : observations) {
            if (observation.issuerId().equals(issuerId)
                    && (latest == null || observation.kind() == CreditObservation.Kind.PRINT)) {
                latest = observation;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** An observed spread and the Tick it arrived on. */
    public record Observed(double spread, long tick) {
    }

    /**
     * @param markStale true from the migration until the first Print or Quote after it: the Mark does not
     *                  yet reflect the Credit Event behind the migration
     */
    public record Migration(RatingBucket from, RatingBucket to, long tick, boolean markStale) {

        Migration withMarkStale(boolean stale) {
            return new Migration(from, to, tick, stale);
        }
    }
}
