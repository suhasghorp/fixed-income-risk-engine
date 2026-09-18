package com.fixedincomerisk.credit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Produces each issuer's Prints and Quotes. Arrivals are Poisson, with at most one of
 * each kind per issuer per step; each observation reveals the Latent Spread plus Gaussian noise, wider for
 * Quotes. After a Credit Event the issuer's Print intensity bursts, λ·(1 + (m − 1)·e^(−decay·age)), so the
 * Mark soon catches up. This is the only path by which the Latent Spread reaches the risk engine.
 */
public final class CreditObservationSimulator {

    private static final double BP = 1e-4;

    private final CreditFactorSimulator factors;
    private final CreditEventParameters events;
    private final List<Issuer> issuers;

    public CreditObservationSimulator(CreditFactorSimulator factors, CreditEventParameters events, List<Issuer> issuers) {
        this.factors = factors;
        this.events = events;
        this.issuers = List.copyOf(issuers);
    }

    /** The issuer's current Print intensity per year, including any post-event burst: the tape's visible pace. */
    public double printIntensity(String issuerId) {
        Issuer issuer = issuers.stream().filter(i -> i.id().equals(issuerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown issuer " + issuerId));
        return printIntensity(issuer);
    }

    private double printIntensity(Issuer issuer) {
        double age = factors.timeSinceCreditEvent(issuer.id());
        if (Double.isInfinite(age)) {
            return issuer.printsPerYear(); // no Credit Event yet (and 0·∞ would be NaN with no decay)
        }
        double burst = (events.printBurstMultiplier() - 1) * Math.exp(-events.printBurstDecayPerYear() * age);
        return issuer.printsPerYear() * (1 + burst);
    }

    /** The observations arriving over the next {@code dt} years, Quotes before Prints for each issuer. */
    public List<CreditObservation> advance(double dt, RandomGenerator random) {
        List<CreditObservation> observations = new ArrayList<>();
        for (Issuer issuer : issuers) {
            observe(issuer, CreditObservation.Kind.QUOTE, issuer.quotesPerYear(), issuer.quoteNoiseBp(), dt, random)
                    .ifPresent(observations::add);
            observe(issuer, CreditObservation.Kind.PRINT, printIntensity(issuer), issuer.printNoiseBp(), dt, random)
                    .ifPresent(observations::add);
        }
        return observations;
    }

    private Optional<CreditObservation> observe(Issuer issuer, CreditObservation.Kind kind, double perYear,
                                                          double noiseBp, double dt, RandomGenerator random) {
        if (random.nextDouble() >= 1 - Math.exp(-perYear * dt)) {
            return Optional.empty();
        }
        double spread = factors.latentSpread(issuer.id()) + noiseBp * BP * random.nextGaussian();
        return Optional.of(new CreditObservation(issuer.id(), kind, spread));
    }
}
