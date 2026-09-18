package com.fixedincomerisk.credit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Simulates the credit factor hierarchy: one Systemic Factor, a Sector Factor per Rating
 * Bucket and an Idiosyncratic Factor per issuer, each an OU process stepped by Euler-Maruyama, plus
 * Credit Events: Poisson jumps that decay only slowly, and may migrate the issuer to a worse Rating Bucket.
 * An issuer's Latent Spread is Systemic + its current bucket's Sector + Idiosyncratic + jump: one flat
 * spread for all its bonds.
 *
 * <p>Only the observables and Rating Migrations are public. The Idiosyncratic Factors, jumps and Latent
 * Spreads are package-private, reachable by the observation simulator but never by the risk engine.
 */
public final class CreditFactorSimulator {

    private static final double BP = 1e-4;

    /** Rating grades from best to worst; a migration moves down this ladder. */
    private static final List<String> RATING_LADDER = List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC");

    private final CreditParameters parameters;
    private final CreditEventParameters events;
    private final Map<RatingBucket, Double> sectorMeans = new LinkedHashMap<>();
    private final Map<String, Issuer> issuers = new LinkedHashMap<>();
    private final Map<RatingBucket, Double> sectors = new LinkedHashMap<>();
    private final Map<String, Double> idiosyncratic = new LinkedHashMap<>();
    private final Map<String, RatingBucket> ratings = new LinkedHashMap<>();
    private final Map<String, Double> jumps = new LinkedHashMap<>();
    private final Map<String, Double> lastCreditEventTimes = new LinkedHashMap<>();
    private double systemic;
    private double time;

    /** Every factor starts at its long-run mean, with no jumps. */
    public CreditFactorSimulator(CreditParameters parameters, CreditEventParameters events, List<RatingBucketSpec> buckets,
                                 List<Issuer> issuers) {
        this.parameters = parameters;
        this.events = events;
        this.systemic = parameters.systemicLongRunMeanBp() * BP;
        for (RatingBucketSpec spec : buckets) {
            sectorMeans.put(spec.bucket(), spec.sectorLongRunMeanBp() * BP);
            sectors.put(spec.bucket(), spec.sectorLongRunMeanBp() * BP);
        }
        for (Issuer issuer : issuers) {
            if (!sectors.containsKey(issuer.initialBucket())) {
                throw new IllegalArgumentException("Issuer " + issuer.id() + " is in unknown Rating Bucket "
                        + issuer.initialBucket());
            }
            this.issuers.put(issuer.id(), issuer);
            idiosyncratic.put(issuer.id(), issuer.idiosyncraticMeanBp() * BP);
            ratings.put(issuer.id(), issuer.initialBucket());
            jumps.put(issuer.id(), 0.0);
        }
        for (CreditEventParameters.ScheduledCreditEvent event : events.scheduled()) {
            if (!this.issuers.containsKey(event.issuerId())) {
                throw new IllegalArgumentException("Scheduled Credit Event for unknown issuer " + event.issuerId());
            }
        }
    }

    /**
     * Advances every factor by {@code dt} years to Tick {@code tick} and applies this tick's Credit Events.
     * The Systemic Factor's shock is supplied (correlated with the short rate); Sector and
     * Idiosyncratic shocks and Credit Events draw from {@code random} in a fixed order. Returns the public
     * part: Rating Migrations.
     */
    public List<RatingMigration> advance(long tick, double dt, double systemicShock, RandomGenerator random) {
        time += dt;
        double sqrtDt = Math.sqrt(dt);
        systemic += parameters.systemicMeanReversion() * (parameters.systemicLongRunMeanBp() * BP - systemic) * dt
                + parameters.systemicVolatilityBp() * BP * sqrtDt * systemicShock;
        sectors.replaceAll((bucket, level) -> ou(level, sectorMeans.get(bucket), parameters.sectorMeanReversion(),
                parameters.sectorVolatilityBp() * BP, dt, sqrtDt, random));
        idiosyncratic.replaceAll((issuerId, level) -> ou(level, issuers.get(issuerId).idiosyncraticMeanBp() * BP,
                parameters.idiosyncraticMeanReversion(), parameters.idiosyncraticVolatilityBp() * BP, dt, sqrtDt,
                random));
        jumps.replaceAll((issuerId, jump) -> jump * Math.exp(-events.jumpDecayPerYear() * dt));

        List<RatingMigration> migrations = new ArrayList<>();
        double eventProbability = 1 - Math.exp(-events.intensityPerYear() * dt);
        for (String issuerId : issuers.keySet()) {
            if (random.nextDouble() < eventProbability) {
                double jumpBp = -events.jumpMeanBp() * Math.log(1 - random.nextDouble());
                int notches = random.nextDouble() < events.migrationProbability()
                        ? 1 + random.nextInt(events.maxNotches())
                        : 0;
                creditEvent(issuerId, jumpBp, notches).ifPresent(migrations::add);
            }
        }
        for (CreditEventParameters.ScheduledCreditEvent event : events.scheduled()) {
            if (event.tick() == tick) {
                creditEvent(event.issuerId(), event.jumpBp(), event.notches()).ifPresent(migrations::add);
            }
        }
        return migrations;
    }

    public CreditObservables observables() {
        return new CreditObservables(systemic, sectors, ratings);
    }

    /** The issuer's full, hidden spread. Package-private: the risk engine must never see it. */
    double latentSpread(String issuerId) {
        return systemic + sectors.get(ratings.get(issuerId)) + idiosyncratic.get(issuerId) + jumps.get(issuerId);
    }

    /** Years since the issuer's latest Credit Event, or infinity if none. Package-private: events are latent. */
    double timeSinceCreditEvent(String issuerId) {
        Double eventTime = lastCreditEventTimes.get(issuerId);
        return eventTime == null ? Double.POSITIVE_INFINITY : time - eventTime;
    }

    /** Jumps the issuer's Latent Spread and, if {@code notches} &gt; 0, downgrades it where a bucket exists. */
    private Optional<RatingMigration> creditEvent(String issuerId, double jumpBp, int notches) {
        jumps.merge(issuerId, jumpBp * BP, Double::sum);
        lastCreditEventTimes.put(issuerId, time);
        if (notches == 0) {
            return Optional.empty();
        }
        RatingBucket from = ratings.get(issuerId);
        return downgrade(from, notches).map(to -> {
            ratings.put(issuerId, to);
            return new RatingMigration(issuerId, from, to);
        });
    }

    /**
     * The worst defined bucket in the same sector that is at most {@code notches} grades below {@code from},
     * or none if no worse bucket is defined in that range.
     */
    private Optional<RatingBucket> downgrade(RatingBucket from, int notches) {
        int fromGrade = RATING_LADDER.indexOf(from.rating());
        RatingBucket best = null;
        int bestGrade = fromGrade;
        for (RatingBucket bucket : sectors.keySet()) {
            int grade = RATING_LADDER.indexOf(bucket.rating());
            if (bucket.sector().equals(from.sector()) && grade > fromGrade && grade <= fromGrade + notches
                    && grade > bestGrade) {
                best = bucket;
                bestGrade = grade;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The issuer's hidden Idiosyncratic Factor. Package-private, for tests in this package. */
    double idiosyncratic(String issuerId) {
        return idiosyncratic.get(issuerId);
    }

    /** The issuers, in order. Package-private, for tests in this package. */
    List<Issuer> issuersForTest() {
        return List.copyOf(issuers.values());
    }

    private static double ou(double level, double mean, double meanReversion, double volatility, double dt,
                             double sqrtDt, RandomGenerator random) {
        return level + meanReversion * (mean - level) * dt + volatility * sqrtDt * random.nextGaussian();
    }
}
