package com.fixedincomerisk.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

/** The credit factor hierarchy and observations, seen from inside the package, where the latent state is visible. */
class CreditSimulationTest {

    private static final double HOUR = 1.0 / (365 * 24);
    private static final double BP = 1e-4;
    private static final RatingBucket A_INDUSTRIALS = new RatingBucket("A", "Industrials");
    private static final RatingBucket BBB_INDUSTRIALS = new RatingBucket("BBB", "Industrials");
    private static final List<RatingBucketSpec> BUCKETS = List.of(
            new RatingBucketSpec(A_INDUSTRIALS, 25), new RatingBucketSpec(BBB_INDUSTRIALS, 70));
    private static final CreditParameters PARAMETERS = new CreditParameters(0.5, 60, 40, 2, 25, 20, 15);

    private static Issuer issuer(String id, RatingBucket bucket, double printsPerYear, double quotesPerYear) {
        return new Issuer(id, id, bucket, 10, printsPerYear, 1, quotesPerYear, 6);
    }

    private static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
    }

    @Test
    void theLatentSpreadIsSystemicPlusSectorPlusIdiosyncratic() {
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, CreditEventParameters.NONE, BUCKETS,
                List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        assertThat(factors.latentSpread("ACME")).isCloseTo((60 + 25 + 10) * BP, within(1e-15));
        RandomGenerator random = random(1);

        for (int i = 0; i < 100; i++) {
            factors.advance(0, HOUR, random.nextGaussian(), random);
            CreditObservables observables = factors.observables();
            assertThat(factors.latentSpread("ACME")).isCloseTo(
                    observables.systemic() + observables.sector(A_INDUSTRIALS) + factors.idiosyncratic("ACME"),
                    within(1e-15));
        }
    }

    @Test
    void eachLevelOfTheHierarchyHasItsOwnCharacter() {
        // Systemic: slow and wide. Sector: moderate. Idiosyncratic: small and fast.
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, CreditEventParameters.NONE, BUCKETS,
                List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        RandomGenerator random = random(2);
        Stats systemic = new Stats();
        Stats sector = new Stats();
        Stats idiosyncratic = new Stats();
        for (int i = 0; i < 20 * 365 * 24; i++) {
            factors.advance(0, HOUR, random.nextGaussian(), random);
            systemic.add(factors.observables().systemic());
            sector.add(factors.observables().sector(A_INDUSTRIALS));
            idiosyncratic.add(factors.idiosyncratic("ACME"));
        }

        // Stationary standard deviations σ/√(2κ): 40bp, 12.5bp and about 2.4bp.
        assertThat(systemic.stdDev() / BP).isBetween(20.0, 60.0);
        assertThat(sector.stdDev() / BP).isCloseTo(12.5, within(3.0));
        assertThat(idiosyncratic.stdDev() / BP).isCloseTo(15 / Math.sqrt(40), within(0.3));
        assertThat(idiosyncratic.mean() / BP).isCloseTo(10, within(0.3));
    }

    @Test
    void printsAndQuotesRevealTheLatentSpreadWithTheirOwnNoise() {
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, CreditEventParameters.NONE, BUCKETS,
                List.of(issuer("BOREAL", BBB_INDUSTRIALS, 4000, 4000)));
        CreditObservationSimulator observations = new CreditObservationSimulator(factors, CreditEventParameters.NONE, factors.issuersForTest());
        RandomGenerator random = random(3);
        Stats printError = new Stats();
        Stats quoteError = new Stats();
        int hours = 5 * 365 * 24;
        for (int i = 0; i < hours; i++) {
            factors.advance(0, HOUR, random.nextGaussian(), random);
            for (CreditObservation observation : observations.advance(HOUR, random)) {
                double error = observation.spread() - factors.latentSpread("BOREAL");
                (observation.kind() == CreditObservation.Kind.PRINT ? printError : quoteError).add(error);
            }
        }

        assertThat(printError.mean() / BP).isCloseTo(0, within(0.1));
        assertThat(printError.stdDev() / BP).isCloseTo(1, within(0.05));
        assertThat(quoteError.stdDev() / BP).isCloseTo(6, within(0.3));
        // At most one of each per step, arriving with probability 1 − e^(−λ·dt): about 16,030 over five years.
        double expected = hours * (1 - Math.exp(-4000 * HOUR));
        assertThat((double) printError.count()).isCloseTo(expected, within(500.0));
        assertThat((double) quoteError.count()).isCloseTo(expected, within(500.0));
    }

    @Test
    void theMarkResetsOnAnObservationWithAPrintWinningOverAQuote() {
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, CreditEventParameters.NONE, BUCKETS,
                List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        CreditMarker marker = new CreditMarker(factors.observables(), factors.issuersForTest());

        marker.update(1, factors.observables(), List.of(
                new CreditObservation("ACME", CreditObservation.Kind.QUOTE, 0.0120),
                new CreditObservation("ACME", CreditObservation.Kind.PRINT, 0.0110)));
        assertThat(marker.marks()).containsEntry("ACME", 0.0110);
        assertThat(marker.lastPrint("ACME")).contains(new CreditMarker.Observed(0.0110, 1));
        assertThat(marker.lastQuote("ACME")).contains(new CreditMarker.Observed(0.0120, 1));

        marker.update(2, factors.observables(), List.of(new CreditObservation("ACME", CreditObservation.Kind.QUOTE, 0.0125)));
        assertThat(marker.marks()).containsEntry("ACME", 0.0125);
    }

    @Test
    void betweenObservationsTheMarkMovesWithSystemicAndItsSectorOnly() {
        CreditObservables before = new CreditObservables(0.0060, Map.of(A_INDUSTRIALS, 0.0025, BBB_INDUSTRIALS, 0.0070),
                Map.of("ACME", A_INDUSTRIALS));
        CreditMarker marker = new CreditMarker(before, List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        double opening = marker.marks().get("ACME");
        assertThat(opening).isCloseTo(0.0095, within(1e-15));

        // Systemic +3bp, own sector −1bp, another bucket +20bp.
        CreditObservables after = new CreditObservables(0.0063, Map.of(A_INDUSTRIALS, 0.0024, BBB_INDUSTRIALS, 0.0090),
                Map.of("ACME", A_INDUSTRIALS));
        marker.update(1, after, List.of());

        assertThat(marker.marks().get("ACME")).isCloseTo(opening + 0.0002, within(1e-15));
        assertThat(marker.lastPrint("ACME")).isEmpty();
    }

    @Test
    void aScheduledCreditEventJumpsTheLatentSpreadAndDowngradesThePublicRating() {
        CreditEventParameters events = new CreditEventParameters(0, 0, 0, 0, 1, 30, 100,
                List.of(new CreditEventParameters.ScheduledCreditEvent("ACME", 5, 80, 1)));
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, events, BUCKETS,
                List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        RandomGenerator random = random(4);
        for (long tick = 1; tick <= 4; tick++) {
            assertThat(factors.advance(tick, HOUR, random.nextGaussian(), random)).isEmpty();
        }
        double before = factors.latentSpread("ACME") - factors.observables().sector(A_INDUSTRIALS);

        List<RatingMigration> migrations = factors.advance(5, HOUR, random.nextGaussian(), random);

        assertThat(migrations).containsExactly(new RatingMigration("ACME", A_INDUSTRIALS, BBB_INDUSTRIALS));
        assertThat(factors.observables().rating("ACME")).isEqualTo(BBB_INDUSTRIALS);
        double after = factors.latentSpread("ACME") - factors.observables().sector(BBB_INDUSTRIALS);
        // Apart from one hour of diffusion, the latent spread (net of its Sector Factor) jumped 80bp.
        assertThat((after - before) / BP).isCloseTo(80, within(2.0));
        assertThat(factors.timeSinceCreditEvent("ACME")).isZero();
    }

    @Test
    void aDowngradeStopsAtTheWorstDefinedBucketAndJumpsDecaySlowly() {
        CreditEventParameters events = new CreditEventParameters(0, 0, 0.5, 0, 1, 1, 0, List.of(
                new CreditEventParameters.ScheduledCreditEvent("BOREAL", 1, 50, 3)));
        CreditParameters frozen = new CreditParameters(0, 60, 0, 0, 0, 0, 0);
        CreditFactorSimulator factors = new CreditFactorSimulator(frozen, events, BUCKETS,
                List.of(issuer("BOREAL", BBB_INDUSTRIALS, 0, 0)));
        RandomGenerator random = random(5);

        assertThat(factors.advance(1, HOUR, random.nextGaussian(), random)).as("no bucket worse than BBB Industrials").isEmpty();
        assertThat(factors.observables().rating("BOREAL")).isEqualTo(BBB_INDUSTRIALS);
        double jumped = factors.latentSpread("BOREAL");
        assertThat(jumped / BP).isCloseTo(60 + 70 + 10 + 50, within(1e-9));

        for (long tick = 2; tick <= 24 * 30; tick++) {
            factors.advance(tick, HOUR, random.nextGaussian(), random);
        }
        // A month later the jump has decayed by e^(−0.5/12), about 4%: it does not mean-revert at once.
        assertThat((factors.latentSpread("BOREAL") / BP) - 140).isCloseTo(50 * Math.exp(-0.5 * (24 * 30 - 1) * HOUR),
                within(1e-6));
    }

    @Test
    void randomCreditEventsArriveAtTheirIntensityAndMayMigrate() {
        CreditEventParameters events = new CreditEventParameters(50, 60, 0.5, 1, 1, 1, 0, List.of());
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, events, BUCKETS,
                List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        RandomGenerator random = random(6);
        int migrations = 0;
        for (long tick = 1; tick <= 24 * 365; tick++) {
            migrations += factors.advance(tick, HOUR, random.nextGaussian(), random).size();
        }

        // At 50 events a year and a migration on every event, the first event downgrades A → BBB; BBB is the
        // worst Industrials bucket, so no later event can migrate.
        assertThat(migrations).isEqualTo(1);
        assertThat(factors.observables().rating("ACME")).isEqualTo(BBB_INDUSTRIALS);
    }

    @Test
    void theMarkRebasesOntoTheNewBucketOnAMigrationButNotByTheLatentJump() {
        CreditObservables before = new CreditObservables(0.0060, Map.of(A_INDUSTRIALS, 0.0025, BBB_INDUSTRIALS, 0.0070),
                Map.of("ACME", A_INDUSTRIALS));
        CreditMarker marker = new CreditMarker(before, List.of(issuer("ACME", A_INDUSTRIALS, 0, 0)));
        double opening = marker.marks().get("ACME");

        CreditObservables migrated = new CreditObservables(0.0061, Map.of(A_INDUSTRIALS, 0.0025, BBB_INDUSTRIALS, 0.0071),
                Map.of("ACME", BBB_INDUSTRIALS));
        marker.update(7, migrated, List.of());

        // Systemic +1bp, and from the A Sector level (25bp) to the BBB one (71bp): +47bp in all.
        assertThat(marker.marks().get("ACME")).isCloseTo(opening + 0.0047, within(1e-15));
        assertThat(marker.lastMigration("ACME")).contains(
                new CreditMarker.Migration(A_INDUSTRIALS, BBB_INDUSTRIALS, 7, true));

        marker.update(8, migrated, List.of(new CreditObservation("ACME", CreditObservation.Kind.QUOTE, 0.0230)));

        assertThat(marker.marks().get("ACME")).isEqualTo(0.0230);
        assertThat(marker.lastMigration("ACME")).hasValueSatisfying(m -> assertThat(m.markStale()).isFalse());
    }

    @Test
    void theBurstRaisesPrintIntensityAfterACreditEventAndDecaysBack() {
        CreditEventParameters events = new CreditEventParameters(0, 0, 0, 0, 1, 30, 100,
                List.of(new CreditEventParameters.ScheduledCreditEvent("ACME", 1, 80, 0)));
        CreditFactorSimulator factors = new CreditFactorSimulator(PARAMETERS, events, BUCKETS,
                List.of(issuer("ACME", A_INDUSTRIALS, 60, 0)));
        CreditObservationSimulator observations = new CreditObservationSimulator(factors, events, factors.issuersForTest());
        RandomGenerator random = random(7);
        assertThat(observations.printIntensity("ACME")).isEqualTo(60);

        factors.advance(1, HOUR, random.nextGaussian(), random);

        assertThat(observations.printIntensity("ACME")).isCloseTo(60 * 30, within(1e-9));
        double previous = observations.printIntensity("ACME");
        for (long tick = 2; tick <= 1000; tick++) {
            factors.advance(tick, HOUR, random.nextGaussian(), random);
            assertThat(observations.printIntensity("ACME")).isLessThan(previous);
            previous = observations.printIntensity("ACME");
        }
        assertThat(previous).isCloseTo(60, within(0.05));
    }

    @Test
    void scheduledCreditEventsParseFromConfiguration() {
        assertThat(CreditEventParameters.ScheduledCreditEvent.parse(" ACME@120:80:1 "))
                .isEqualTo(new CreditEventParameters.ScheduledCreditEvent("ACME", 120, 80, 1));
    }

    private static final class Stats {
        private int count;
        private double sum;
        private double sumSquares;

        void add(double value) {
            count++;
            sum += value;
            sumSquares += value * value;
        }

        int count() {
            return count;
        }

        double mean() {
            return sum / count;
        }

        double stdDev() {
            return Math.sqrt(sumSquares / count - mean() * mean());
        }
    }
}
