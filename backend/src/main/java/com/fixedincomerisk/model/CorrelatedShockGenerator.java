package com.fixedincomerisk.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Produces each tick's correlated standard normal shocks, one per named factor, as Z = L·ε from
 * independent draws ε of the single seeded source and the Cholesky factor L computed once.
 *
 * <p>{@value #BASIS} is the one factor with many instances: every futures contract has its own Basis, and
 * each takes the shared draws of the factors before it plus one fresh draw of its own. So every contract's
 * Basis has the configured correlations with the short rates, the Systemic Factor and FX, while the
 * contracts are correlated with each other only through those shared factors.
 *
 * <p>Because each instance substitutes its own draw for the Basis row's own slot, there is no single
 * Basis innovation for a later factor to correlate with, so {@value #BASIS} must be the last factor. That
 * is checked at construction rather than left to produce quietly wrong correlations.
 */
public final class CorrelatedShockGenerator {

    /** The one factor that has an instance per futures contract. */
    public static final String BASIS = "basis";

    private final CorrelationMatrix correlations;
    private final List<String> singletons;
    private final boolean hasBasis;
    private final double[] basisRow;

    public CorrelatedShockGenerator(CorrelationMatrix correlations) {
        this.correlations = correlations;
        this.hasBasis = correlations.has(BASIS);
        List<String> factors = correlations.factors();
        if (hasBasis && correlations.indexOf(BASIS) != factors.size() - 1) {
            throw new IllegalArgumentException("'" + BASIS + "' has one instance per futures contract, so it must "
                    + "be the last factor in risk.correlation.factors; got " + factors);
        }
        this.singletons = hasBasis ? factors.subList(0, factors.size() - 1) : factors;
        this.basisRow = hasBasis ? correlations.choleskyRow(BASIS) : new double[0];
    }

    /**
     * Draws the next tick's shocks in a fixed order: one independent draw per singleton factor, in matrix
     * order, then one per futures contract.
     */
    public Shocks next(RandomGenerator random, int futuresContracts) {
        double[] independent = new double[singletons.size()];
        for (int i = 0; i < independent.length; i++) {
            independent[i] = random.nextGaussian();
        }
        Map<String, Double> byFactor = new LinkedHashMap<>();
        for (int i = 0; i < singletons.size(); i++) {
            double[] row = correlations.choleskyRow(singletons.get(i));
            double shock = 0;
            for (int k = 0; k <= i; k++) {
                shock += row[k] * independent[k];
            }
            byFactor.put(singletons.get(i), shock);
        }
        List<Double> basis = new ArrayList<>(futuresContracts);
        if (hasBasis) {
            int own = singletons.size();
            for (int c = 0; c < futuresContracts; c++) {
                double shock = 0;
                for (int k = 0; k < own; k++) {
                    shock += basisRow[k] * independent[k];
                }
                basis.add(shock + basisRow[own] * random.nextGaussian());
            }
        }
        return new Shocks(byFactor, basis);
    }

    /**
     * One tick's correlated standard normal shocks.
     *
     * @param byFactor one shock per singleton factor, in matrix order
     * @param basis    one per futures contract, in contract order
     */
    public record Shocks(Map<String, Double> byFactor, List<Double> basis) {

        public Shocks {
            byFactor = new LinkedHashMap<>(byFactor);
            basis = List.copyOf(basis);
        }

        /** The shock for one named factor; fails rather than defaulting to zero for an unknown name. */
        public double of(String factor) {
            Double shock = byFactor.get(factor);
            if (shock == null) {
                throw new IllegalArgumentException("No shock for factor '" + factor + "'; this tick has "
                        + byFactor.keySet());
            }
            return shock;
        }

        /** Zero for a factor that is not simulated in this session, which is how an absent pair behaves. */
        public double orZero(String factor) {
            return byFactor.getOrDefault(factor, 0.0);
        }
    }
}
